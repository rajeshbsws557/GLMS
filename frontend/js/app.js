/**
 * ============================================================================
 * ফাইল: app.js — ড্যাশবোর্ড লজিক
 * ============================================================================
 *
 * এটি ড্যাশবোর্ডের প্রধান JS ফাইল। এটি যা হ্যান্ডেল করে:
 *
 *   - অথেনটিকেশন গার্ড (লগইন না থাকলে রিডাইরেক্ট)
 *   - API থেকে কন্টাক্ট এবং লোন লোড করা
 *   - "Lent" এবং "Borrowed" কলামে লোন কার্ডগুলো রেন্ডার করা
 *   - সারসংক্ষেপ হিসাব (মোট, নিট ব্যালেন্স)
 *   - কন্টাক্ট যোগ করা, লোন তৈরি করা, পরিশোধ রেকর্ড করা
 *   - পরিশোধের ইতিহাস দেখা
 *   - মোডাল খোলা/বন্ধ করা, টোস্ট নোটিফিকেশন, লগআউট
 *
 * ডেটা ফ্লো (ভাইভার জন্য):
 *
 *   Browser (app.js)           Java API              MySQL
 *   ────────────────────       ─────────────         ──────
 *   fetch(url, {body})   -->   HttpExchange      -->  prepared statement
 *   gets JSON response   <--   Gson .toJson()    <--  query result
 *
 * মূল JS ধারণা:
 *   async/await — Promises হ্যান্ডেল করার পরিচ্ছন্ন উপায় (.then() চেইন ছাড়া)
 *   Array methods — .filter(), .reduce(), .map(), .find(), .forEach()
 *   Template literals — backtick string যেখানে ${expression} এম্বেড করা যায়
 *   DOM manipulation — createElement, innerHTML, textContent
 *   XSS prevention — escapeHtml() ইউজার-সাপ্লাইড স্ট্রিংগুলোকে নিরাপদ করে
 *
 * ============================================================================
 */

// ---------------------------------------------------------------------------
// গ্লোবাল স্টেট (Global state)
// ---------------------------------------------------------------------------
// ক্যাশ করা ডেটা যাতে আমরা অতিরিক্ত API কল ছাড়াই লোন/কন্টাক্ট রেফারেন্স করতে পারি।

var currentUserId = null;   // localStorage থেকে
var contactsList  = [];     // { contact_id, contact_name, phone_number } এর array
var loansList     = [];     // লোন অবজেক্টের array (loadLoans দেখুন)


// ---------------------------------------------------------------------------
// Init — স্ক্রিপ্ট লোড হওয়ার সাথে সাথে চলে
// ---------------------------------------------------------------------------
(async function init() {

    // --- অথেনটিকেশন গার্ড (auth guard) ---
    currentUserId = localStorage.getItem('user_id');
    if (!currentUserId) {
        window.location.href = 'index.html';
        return;
    }

    document.getElementById('display-user-name').textContent =
        localStorage.getItem('full_name') || 'User';

    // আজকের তারিখ দিয়ে ঋণের তারিখ প্রি-ফিল করুন (YYYY-MM-DD)
    document.getElementById('loan-date').value =
        new Date().toISOString().split('T')[0];

    // ডেটা ফেচ করুন
    try {
        await loadContacts();
        await loadLoans();
    } catch (e) {
        console.error('Init error:', e);
        showToast('Failed to load data. Check your connection.', 'error');
    }

    // কন্টেন্টের জন্য লোডিং স্পিনার বদলান
    document.getElementById('loading-state').style.display = 'none';
    document.getElementById('main-content').style.display  = 'block';
})();


// ===========================================================================
//  ডেটা লোডিং (DATA LOADING)
// ===========================================================================

/**
 * loadContacts — GET /contacts?user_id=X
 *
 * ইউজারের কন্টাক্টগুলো ফেচ করে এবং `contactsList`-এ সেভ করে।
 * তারপর "New Loan" ফর্মে <select> পূরণ করতে populateContactDropdown() কল করে।
 */
async function loadContacts() {
    var res  = await fetch(API_BASE_URL + 'contacts?user_id=' + currentUserId);
    var data = await res.json();

    if (data.success) {
        contactsList = data.contacts;
        populateContactDropdown();
    }
}

/**
 * populateContactDropdown — কন্টাক্টগুলোর জন্য <select> অপশনগুলো রি-বিল্ড করে।
 *
 * আমরা প্রথমে ড্রপডাউন ক্লিয়ার করি (প্লেসহোল্ডার বাদে), তারপর প্রতিটি কন্টাক্টের
 * জন্য একটি <option> তৈরি করি। option.value = contact_id যা সার্ভারে
 * পাঠানো হয়; option.textContent হলো যা ব্যবহারকারী দেখেন।
 */
function populateContactDropdown() {
    var select = document.getElementById('loan-contact');
    select.innerHTML = '<option value="">Choose a contact\u2026</option>';

    contactsList.forEach(function (c) {
        var opt = document.createElement('option');
        opt.value       = c.contact_id;
        opt.textContent = c.contact_name;
        select.appendChild(opt);
    });
}

/**
 * loadLoans — GET /loans?user_id=X
 *
 * ব্যাকএন্ড লোনগুলোকে কন্টাক্টের সাথে JOIN করে পাঠায় (যাতে আমরা নাম পাই) এবং
 * একটি `total_paid` ফিল্ড অন্তর্ভুক্ত করে যা SUM সাবকোয়েরি দ্বারা গণনা করা হয়।
 * ফেচ করার পরে আমরা কার্ড রেন্ডার করি এবং সারসংক্ষেপ আপডেট করি।
 */
async function loadLoans() {
    var res  = await fetch(API_BASE_URL + 'loans?user_id=' + currentUserId);
    var data = await res.json();

    if (data.success) {
        loansList = data.loans;
        renderLoans();
        updateSummary();
    }
}


// ===========================================================================
//  রেন্ডারিং (RENDERING)
// ===========================================================================

/**
 * renderLoans — loansList কে Lent / Borrowed এ ভাগ করে এবং HTML তৈরি করে।
 *
 * ব্যবহৃত Array মেথড (ভাইভার জন্য গুরুত্বপূর্ণ):
 *   .filter(fn)  — শুধুমাত্র সেই আইটেমগুলো নিয়ে নতুন array দেয় যেখানে fn সত্য (true)
 *   .map(fn)     — প্রতিটি আইটেমকে রূপান্তর করে এবং একটি নতুন array দেয়
 *   .join('')     — array স্ট্রিংগুলোকে একটি বড় স্ট্রিংয়ে যুক্ত করে
 */
function renderLoans() {
    var lent     = loansList.filter(function (l) { return l.loan_type === 'Lent'; });
    var borrowed = loansList.filter(function (l) { return l.loan_type === 'Borrowed'; });

    // --- ধার দেওয়া (lent) কলাম ---
    var lentBox = document.getElementById('loans-lent');
    if (lent.length === 0) {
        lentBox.innerHTML = '<p class="empty-msg">No loans lent yet. Click "+ New Loan" to start.</p>';
    } else {
        lentBox.innerHTML = lent.map(createLoanCard).join('');
    }

    // --- ধার নেওয়া (borrowed) কলাম ---
    var borrowedBox = document.getElementById('loans-borrowed');
    if (borrowed.length === 0) {
        borrowedBox.innerHTML = '<p class="empty-msg">No borrowed loans yet. Click "+ New Loan" to start.</p>';
    } else {
        borrowedBox.innerHTML = borrowed.map(createLoanCard).join('');
    }

    // ব্যাজ কাউন্ট
    document.getElementById('lent-count').textContent     = lent.length;
    document.getElementById('borrowed-count').textContent = borrowed.length;
}


/**
 * createLoanCard — একটি লোন কার্ডের জন্য HTML স্ট্রিং রিটার্ন করে।
 *
 * কার্ডে দেখায়: কন্টাক্টের নাম, পরিমাণ, তারিখ, স্ট্যাটাস ব্যাজ,
 * একটি চিকন প্রগ্রেস বার, এবং অ্যাকশন বোতাম।
 *
 * ডেরিভড ভ্যালু (বাকি, প্রগ্রেস %) DB তে সেভ করার বদলে এখানেই
 * হিসাব করা হয় — রিডানডেন্সি এড়ায়।
 *
 * @param {Object} loan
 * @returns {string}
 */
function createLoanCard(loan) {

    var remaining = Math.max(0, loan.amount - loan.total_paid);
    var pct       = Math.min(100, (loan.total_paid / loan.amount) * 100);

    // CSS ক্লাসে স্ট্যাটাস ম্যাপ করুন
    var badgeClass = 'badge-unpaid';
    if (loan.status === 'Partially Paid') badgeClass = 'badge-partial';
    if (loan.status === 'Settled')        badgeClass = 'badge-settled';

    // কার্ড টাইপ ক্লাস বাম দিকের অ্যাকসেন্ট স্ট্রাইপের রঙ নিয়ন্ত্রণ করে
    var typeClass = loan.loan_type === 'Lent' ? 'type-lent' : 'type-borrowed';

    // শর্তাধীন সেকশন
    var dueLine = '';
    if (loan.due_date) {
        dueLine = '<span>Due: ' + formatDate(loan.due_date) + '</span>';
    }

    var notesLine = '';
    if (loan.notes) {
        notesLine = '<p class="text-xs mt-1.5 italic" style="color:var(--text-dim)">' +
                    escapeHtml(loan.notes) + '</p>';
    }

    var remainingLine;
    if (remaining > 0) {
        remainingLine = '<span style="color:var(--red)">\u20B9' + fmtNum(remaining) + ' remaining</span>';
    } else {
        remainingLine = '<span style="color:var(--green)">Fully settled</span>';
    }

    // অ্যাকশন বোতাম — সেটল হয়ে গেলে "Log Payment" লুকান
    var actions;
    if (loan.status !== 'Settled') {
        actions =
            '<button onclick="openRepaymentModal(' + loan.loan_id + ')" class="btn btn-outline btn-sm flex-1">Log Payment</button>' +
            '<button onclick="viewRepaymentHistory(' + loan.loan_id + ')" class="btn btn-ghost btn-sm flex-1">History</button>' +
            '<button onclick="handleDeleteLoan(' + loan.loan_id + ')" class="btn btn-ghost btn-sm" style="color:var(--red)" title="Delete Loan">&times;</button>';
    } else {
        actions =
            '<button onclick="viewRepaymentHistory(' + loan.loan_id + ')" class="btn btn-ghost btn-sm flex-1">View History</button>' +
            '<button onclick="handleDeleteLoan(' + loan.loan_id + ')" class="btn btn-ghost btn-sm" style="color:var(--red)" title="Delete Loan">&times;</button>';
    }

    return '' +
        '<div class="card loan-card ' + typeClass + '">' +
            '<div class="flex justify-between items-start mb-1">' +
                '<div>' +
                    '<p class="font-medium text-sm">' + escapeHtml(loan.contact_name) + '</p>' +
                    '<p class="text-xl font-bold mt-0.5">\u20B9' + fmtNum(loan.amount) + '</p>' +
                '</div>' +
                '<span class="badge ' + badgeClass + '">' + loan.status + '</span>' +
            '</div>' +

            '<div class="flex flex-wrap gap-x-4 text-xs mt-1" style="color:var(--text-faint)">' +
                '<span>' + formatDate(loan.loan_date) + '</span>' +
                dueLine +
            '</div>' +

            notesLine +

            '<div class="mt-3">' +
                '<div class="flex justify-between text-xs mb-1" style="color:var(--text-faint)">' +
                    '<span>Paid \u20B9' + fmtNum(loan.total_paid) + ' / \u20B9' + fmtNum(loan.amount) + '</span>' +
                    '<span>' + pct.toFixed(0) + '%</span>' +
                '</div>' +
                '<div class="progress-track"><div class="progress-fill" style="width:' + pct + '%"></div></div>' +
                '<p class="text-xs mt-1">' + remainingLine + '</p>' +
            '</div>' +

            '<div class="flex gap-2 mt-3 pt-3 divider">' + actions + '</div>' +
        '</div>';
}


/**
 * updateSummary — মোট হিসাব করে এবং সারসংক্ষেপ কার্ডে লেখে।
 *
 * মান যোগ করতে .reduce() ব্যবহার করে:
 *   array.reduce((accumulator, item) => accumulator + item.field, startValue)
 *
 *   [10, 20, 30].reduce((s, n) => s + n, 0) => 60
 */
function updateSummary() {
    var lent     = loansList.filter(function (l) { return l.loan_type === 'Lent'; });
    var borrowed = loansList.filter(function (l) { return l.loan_type === 'Borrowed'; });

    var totalLent     = lent.reduce(function (s, l) { return s + l.amount; }, 0);
    var totalBorrowed = borrowed.reduce(function (s, l) { return s + l.amount; }, 0);

    var lentOutstanding = lent
        .filter(function (l) { return l.status !== 'Settled'; })
        .reduce(function (s, l) { return s + (l.amount - l.total_paid); }, 0);

    var borrowedOutstanding = borrowed
        .filter(function (l) { return l.status !== 'Settled'; })
        .reduce(function (s, l) { return s + (l.amount - l.total_paid); }, 0);

    var net = lentOutstanding - borrowedOutstanding;

    document.getElementById('total-lent').textContent       = '\u20B9' + fmtNum(totalLent);
    document.getElementById('total-borrowed').textContent    = '\u20B9' + fmtNum(totalBorrowed);
    document.getElementById('lent-remaining').textContent    = '\u20B9' + fmtNum(lentOutstanding) + ' outstanding';
    document.getElementById('borrowed-remaining').textContent = '\u20B9' + fmtNum(borrowedOutstanding) + ' outstanding';

    var netEl    = document.getElementById('net-balance');
    var netLabel = document.getElementById('net-label');

    if (net > 0) {
        netEl.textContent  = '+\u20B9' + fmtNum(net);
        netEl.style.color  = 'var(--green)';
        netLabel.textContent = 'Others owe you more';
    } else if (net < 0) {
        netEl.textContent  = '-\u20B9' + fmtNum(Math.abs(net));
        netEl.style.color  = 'var(--red)';
        netLabel.textContent = 'You owe more to others';
    } else {
        netEl.textContent  = '\u20B9' + fmtNum(0);
        netEl.style.color  = 'var(--text-main)';
        netLabel.textContent = 'All settled';
    }
}


// ===========================================================================
//  ফর্ম হ্যান্ডলার (FORM HANDLERS)
// ===========================================================================

/**
 * handleAddContact — POST /contacts
 *
 * একটি নতুন কন্টাক্ট তৈরি করে, তারপর কন্টাক্ট লিস্ট রিলোড করে (যা লোন
 * ফর্মে ড্রপডাউনও রিফ্রেশ করে)।
 */
async function handleAddContact(event) {
    event.preventDefault();

    var name  = document.getElementById('contact-name').value.trim();
    var email = document.getElementById('contact-email').value.trim();
    var phone = document.getElementById('contact-phone').value.trim();

    if (!name) { showToast('Enter a contact name.', 'error'); return false; }

    try {
        var res  = await fetch(API_BASE_URL + 'contacts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user_id: currentUserId,
                contact_name: name,
                email: email || null,
                phone_number: phone || null
            })
        });
        var data = await res.json();

        if (data.success) {
            showToast('Contact "' + name + '" added.', 'success');
            closeModal('modal-contact');
            document.getElementById('form-add-contact').reset();
            await loadContacts();
        } else {
            showToast(data.message || 'Failed to add contact.', 'error');
        }
    } catch (e) {
        console.error(e);
        showToast('Network error.', 'error');
    }
    return false;
}

/**
 * handleAddLoan — POST /loans
 *
 * একটি নতুন লোন রেকর্ড তৈরি করে, তারপর সঠিক কলামে দেখানোর জন্য লোন লিস্ট রিলোড করে।
 */
async function handleAddLoan(event) {
    event.preventDefault();

    var contactId = document.getElementById('loan-contact').value;
    var loanType  = document.getElementById('loan-type').value;
    var amount    = document.getElementById('loan-amount').value;
    var loanDate  = document.getElementById('loan-date').value;
    var dueDate   = document.getElementById('loan-due-date').value;
    var notes     = document.getElementById('loan-notes').value.trim();

    if (!contactId || !loanType || !amount || !loanDate) {
        showToast('Fill in all required fields.', 'error');
        return false;
    }
    if (parseFloat(amount) <= 0) {
        showToast('Amount must be positive.', 'error');
        return false;
    }

    try {
        var res = await fetch(API_BASE_URL + 'loans', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user_id:    currentUserId,
                contact_id: parseInt(contactId, 10),
                loan_type:  loanType,
                amount:     parseFloat(amount),
                loan_date:  loanDate,
                due_date:   dueDate || null,
                notes:      notes || null
            })
        });
        var data = await res.json();

        if (data.success) {
            showToast('Loan created.', 'success');
            closeModal('modal-loan');
            document.getElementById('form-add-loan').reset();
            // আজকের তারিখ পুনরায় সেট করুন
            document.getElementById('loan-date').value = new Date().toISOString().split('T')[0];
            await loadLoans();
        } else {
            showToast(data.message || 'Failed to create loan.', 'error');
        }
    } catch (e) {
        console.error(e);
        showToast('Network error.', 'error');
    }
    return false;
}


/**
 * openRepaymentModal — নির্বাচিত লোনের তথ্য (কন্টাক্টের নাম, পরিমাণ, বাকি)
 * দিয়ে রিপেমেন্ট মোডাল পূরণ করে এবং এটি খোলে।
 *
 * .find() — ক্যাশ করা loansList এ খোঁজে এবং প্রথম ম্যাচ রিটার্ন করে।
 */
function openRepaymentModal(loanId) {
    var loan = loansList.find(function (l) { return l.loan_id === loanId; });
    if (!loan) { showToast('Loan not found — try refreshing.', 'error'); return; }

    var remaining = Math.max(0, loan.amount - loan.total_paid);

    document.getElementById('repayment-contact').textContent   = loan.contact_name;
    document.getElementById('repayment-amount').textContent     = '\u20B9' + fmtNum(loan.amount);
    document.getElementById('repayment-remaining').textContent  = '\u20B9' + fmtNum(remaining);
    document.getElementById('repayment-loan-id').value          = loanId;

    var payInput       = document.getElementById('repayment-pay-amount');
    payInput.max       = remaining;
    payInput.placeholder = 'Max \u20B9' + fmtNum(remaining);
    payInput.value     = '';

    openModal('modal-repayment');
}

/**
 * handleAddRepayment — POST /repayments
 *
 * ব্যাকএন্ড insert + status-update কে একটি TRANSACTION-এ মুড়ে দেয়:
 *   ১. INSERT repayment
 *   ২. লোনের জন্য SELECT SUM(amount_paid)
 *   ৩. loans.status 'Partially Paid' বা 'Settled' এ UPDATE
 *
 * এরপর আমরা লোন রিলোড করি যাতে কার্ডে নতুন স্ট্যাটাস/প্রগ্রেস প্রতিফলিত হয়।
 */
async function handleAddRepayment(event) {
    event.preventDefault();

    var loanId = document.getElementById('repayment-loan-id').value;
    var amount = parseFloat(document.getElementById('repayment-pay-amount').value);

    if (!loanId || !amount || amount <= 0) {
        showToast('Enter a valid payment amount.', 'error');
        return false;
    }

    try {
        var res = await fetch(API_BASE_URL + 'repayments', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                loan_id:     parseInt(loanId, 10),
                amount_paid: amount
            })
        });
        var data = await res.json();

        if (data.success) {
            var msg = 'Payment of \u20B9' + fmtNum(amount) + ' logged.';
            if (data.new_status === 'Settled') {
                msg += ' Loan fully settled!';
            } else if (data.remaining > 0) {
                msg += ' \u20B9' + fmtNum(data.remaining) + ' remaining.';
            }
            showToast(msg, 'success');
            closeModal('modal-repayment');
            document.getElementById('form-repayment').reset();
            await loadLoans();
        } else {
            showToast(data.message || 'Failed to log repayment.', 'error');
        }
    } catch (e) {
        console.error(e);
        showToast('Network error.', 'error');
    }
    return false;
}


/**
 * viewRepaymentHistory — GET /repayments?loan_id=X
 *
 * একটি লোনের সব পেমেন্ট ফেচ করে এবং একটি মোডালে দেখায়।
 */
async function viewRepaymentHistory(loanId) {
    var loan = loansList.find(function (l) { return l.loan_id === loanId; });

    try {
        var res  = await fetch(API_BASE_URL + 'repayments?loan_id=' + loanId);
        var data = await res.json();
        var box  = document.getElementById('repayment-history-content');

        if (data.success && data.repayments.length > 0) {
            var html = '';

            // লোন সারসংক্ষেপ ব্লক
            if (loan) {
                var rem = Math.max(0, loan.amount - loan.total_paid);
                html +=
                    '<div class="card mb-4" style="padding:0.85rem">' +
                        '<p class="font-medium text-sm">' + escapeHtml(loan.contact_name) + '</p>' +
                        '<div class="flex justify-between text-xs mt-1" style="color:var(--text-dim)">' +
                            '<span>Amount</span><span>\u20B9' + fmtNum(loan.amount) + '</span>' +
                        '</div>' +
                        '<div class="flex justify-between text-xs" style="color:var(--text-dim)">' +
                            '<span>Paid</span><span style="color:var(--green)">\u20B9' + fmtNum(loan.total_paid) + '</span>' +
                        '</div>' +
                        '<div class="flex justify-between text-xs" style="color:var(--text-dim)">' +
                            '<span>Remaining</span><span style="color:var(--red)">\u20B9' + fmtNum(rem) + '</span>' +
                        '</div>' +
                    '</div>';
            }

            // ব্যক্তিগত পেমেন্টগুলো
            data.repayments.forEach(function (r, i) {
                html +=
                    '<div class="repayment-row">' +
                        '<span style="color:var(--text-dim)">#' + (i + 1) + ' &middot; ' + formatDateTime(r.payment_date) + '</span>' +
                        '<span class="font-medium" style="color:var(--green)">+\u20B9' + fmtNum(r.amount_paid) + '</span>' +
                    '</div>';
            });

            box.innerHTML = html;
        } else {
            box.innerHTML = '<p class="empty-msg">No repayments recorded yet.</p>';
        }

        openModal('modal-history');
    } catch (e) {
        console.error(e);
        showToast('Failed to load history.', 'error');
    }
}

// ===========================================================================
//  Manage Contacts & Deletions
// ===========================================================================

function openManageContactsModal() {
    renderContactsManageList();
    openModal('modal-manage-contacts');
}

function renderContactsManageList() {
    var box = document.getElementById('manage-contacts-list');
    if (contactsList.length === 0) {
        box.innerHTML = '<p class="empty-msg">No contacts available.</p>';
        return;
    }
    
    var html = '';
    contactsList.forEach(function(c) {
        var emailStr = c.email ? c.email : 'No email';
        var phoneStr = c.phone_number ? c.phone_number : 'No phone';
        html += '<div class="card mb-2" style="padding: 0.75rem;">' +
            '<div class="flex justify-between items-center">' +
                '<div>' +
                    '<p class="font-medium">' + escapeHtml(c.contact_name) + '</p>' +
                    '<p class="text-xs" style="color:var(--text-dim)">' + escapeHtml(emailStr) + ' &middot; ' + escapeHtml(phoneStr) + '</p>' +
                '</div>' +
                '<div class="flex gap-2">' +
                    '<button onclick="openEditContactModal(' + c.contact_id + ')" class="btn btn-ghost btn-sm text-blue-500">Edit</button>' +
                    '<button onclick="handleDeleteContact(' + c.contact_id + ')" class="btn btn-ghost btn-sm text-red-500">Delete</button>' +
                '</div>' +
            '</div>' +
        '</div>';
    });
    box.innerHTML = html;
}

function openEditContactModal(contactId) {
    var contact = contactsList.find(function(c) { return c.contact_id === contactId; });
    if (!contact) return;
    
    document.getElementById('edit-contact-id').value = contact.contact_id;
    document.getElementById('edit-contact-name').value = contact.contact_name;
    document.getElementById('edit-contact-email').value = contact.email || '';
    document.getElementById('edit-contact-phone').value = contact.phone_number || '';
    
    openModal('modal-edit-contact');
}

async function handleEditContact(event) {
    event.preventDefault();
    var contactId = document.getElementById('edit-contact-id').value;
    var name = document.getElementById('edit-contact-name').value.trim();
    var email = document.getElementById('edit-contact-email').value.trim();
    var phone = document.getElementById('edit-contact-phone').value.trim();
    
    if (!name) return false;
    
    try {
        var res = await fetch(API_BASE_URL + 'contacts', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user_id: parseInt(currentUserId, 10),
                contact_id: parseInt(contactId, 10),
                contact_name: name,
                email: email || null,
                phone_number: phone || null
            })
        });
        var data = await res.json();
        if (data.success) {
            showToast('Contact updated successfully.', 'success');
            closeModal('modal-edit-contact');
            await loadContacts();
            await loadLoans(); // names might have changed
            renderContactsManageList();
        } else {
            showToast(data.message || 'Failed to update contact.', 'error');
        }
    } catch (e) {
        showToast('Network error.', 'error');
    }
    return false;
}

async function handleDeleteContact(contactId) {
    if (!confirm('Are you sure you want to delete this contact? All their loans and repayment history will also be permanently deleted!')) {
        return;
    }
    
    try {
        var res = await fetch(API_BASE_URL + 'contacts?contact_id=' + contactId + '&user_id=' + currentUserId, {
            method: 'DELETE'
        });
        var data = await res.json();
        if (data.success) {
            showToast('Contact deleted.', 'success');
            await loadContacts();
            await loadLoans(); // remove their loans
            renderContactsManageList();
        } else {
            showToast(data.message || 'Failed to delete contact.', 'error');
        }
    } catch (e) {
        showToast('Network error.', 'error');
    }
}

async function handleDeleteLoan(loanId) {
    if (!confirm('Are you sure you want to delete this loan? All repayment history will be permanently lost!')) {
        return;
    }
    
    try {
        var res = await fetch(API_BASE_URL + 'loans?loan_id=' + loanId + '&user_id=' + currentUserId, {
            method: 'DELETE'
        });
        var data = await res.json();
        if (data.success) {
            showToast('Loan deleted.', 'success');
            await loadLoans();
        } else {
            showToast(data.message || 'Failed to delete loan.', 'error');
        }
    } catch (e) {
        showToast('Network error.', 'error');
    }
}


// ===========================================================================
//  মোডাল হেল্পার (MODAL HELPERS)
// ===========================================================================

function openModal(id)  {
    document.getElementById(id).classList.add('active');
    document.body.style.overflow = 'hidden';
}
function closeModal(id) {
    document.getElementById(id).classList.remove('active');
    document.body.style.overflow = '';
}
function handleOverlayClick(event, id) {
    if (event.target === event.currentTarget) closeModal(id);
}


// ===========================================================================
//  লগআউট (LOGOUT)
// ===========================================================================

function handleLogout() {
    localStorage.clear();
    window.location.href = 'index.html';
}


// ===========================================================================
//  ইউটিলিটি ফাংশন (UTILITY FUNCTIONS)
// ===========================================================================

/**
 * fmtNum — ভারতীয় লোকাল কমা সহ একটি নম্বরকে ২ দশমিক স্থান পর্যন্ত ফর্ম্যাট করে।
 *
 * toLocaleString('en-IN') লাখ/কোটি সিস্টেম ব্যবহার করে:
 *   1234567.5 => "12,34,567.50"
 */
function fmtNum(n) {
    return Number(n).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

/** formatDate — "2024-01-15" কে "Jan 15, 2024" তে পরিণত করে */
function formatDate(str) {
    if (!str) return 'N/A';
    return new Date(str + 'T00:00:00').toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric'
    });
}

/** formatDateTime — একটি টাইমস্ট্যাম্পকে "Jan 15, 2024, 02:30 PM" তে পরিণত করে */
function formatDateTime(str) {
    if (!str) return 'N/A';
    return new Date(str).toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit'
    });
}

/**
 * escapeHtml — < > & " কে নিরাপদ এন্টিটিতে রূপান্তর করে XSS প্রতিরোধ করে।
 *
 * কীভাবে: স্ট্রিংটিকে textContent হিসাবে সেট করুন (অটো-এসকেপ করে), তারপর এটি
 * innerHTML থেকে ফেরত পড়ুন।
 */
function escapeHtml(str) {
    var d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

/**
 * showToast — ছোট নোটিফিকেশন যা ৩.৫ সেকেন্ড পর স্বয়ংক্রিয়ভাবে বন্ধ হয়ে যায়।
 *
 * auth.js থেকে নকল করা কারণ dashboard.html app.js লোড করে (auth.js নয়)।
 * একটি বড় প্রজেক্টে আপনি এটি একটি শেয়ার্ড ফাইলে রাখতে পারেন।
 */
function showToast(message, type) {
    type = type || 'info';
    var container = document.getElementById('toast-container');

    var el = document.createElement('div');
    el.className = 'toast-item toast-' + type;
    el.textContent = message;
    container.appendChild(el);

    setTimeout(function () {
        el.style.animation = 'slideOut 0.25s ease forwards';
        setTimeout(function () { el.remove(); }, 250);
    }, 3500);
}
