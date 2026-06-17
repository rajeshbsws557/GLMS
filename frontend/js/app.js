/**
 * ============================================================================
 * FILE: app.js — Dashboard Logic
 * ============================================================================
 *
 * This is the main JS file for the dashboard. It handles:
 *
 *   - Authentication guard (redirect if not logged in)
 *   - Loading contacts and loans from the PHP API
 *   - Rendering loan cards in the "Lent" and "Borrowed" columns
 *   - Summary calculations (totals, net balance)
 *   - Adding contacts, creating loans, logging repayments
 *   - Viewing repayment history
 *   - Modal open/close, toast notifications, logout
 *
 * DATA FLOW (for viva):
 *
 *   Browser (app.js)           PHP API               MySQL
 *   ────────────────────       ─────────────         ──────
 *   fetch(url, {body})   -->   reads php://input -->  prepared statement
 *   gets JSON response   <--   json_encode()    <--   query result
 *
 * KEY JS CONCEPTS:
 *   async/await — clean way to handle Promises (no .then() chains)
 *   Array methods — .filter(), .reduce(), .map(), .find(), .forEach()
 *   Template literals — backtick strings with ${expression} embedding
 *   DOM manipulation — createElement, innerHTML, textContent
 *   XSS prevention — escapeHtml() sanitises user-supplied strings
 *
 * ============================================================================
 */

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
// Cached data so we can reference loans/contacts without extra API calls.

var currentUserId = null;   // from localStorage
var contactsList  = [];     // array of { contact_id, contact_name, phone_number }
var loansList     = [];     // array of loan objects (see loadLoans)


// ---------------------------------------------------------------------------
// Init — runs immediately on script load
// ---------------------------------------------------------------------------
(async function init() {

    // --- auth guard ---
    currentUserId = localStorage.getItem('user_id');
    if (!currentUserId) {
        window.location.href = 'index.html';
        return;
    }

    document.getElementById('display-user-name').textContent =
        localStorage.getItem('full_name') || 'User';

    // Pre-fill loan date with today (YYYY-MM-DD)
    document.getElementById('loan-date').value =
        new Date().toISOString().split('T')[0];

    // Fetch data
    try {
        await loadContacts();
        await loadLoans();
    } catch (e) {
        console.error('Init error:', e);
        showToast('Failed to load data. Check your connection.', 'error');
    }

    // Swap loading spinner for content
    document.getElementById('loading-state').style.display = 'none';
    document.getElementById('main-content').style.display  = 'block';
})();


// ===========================================================================
//  DATA LOADING
// ===========================================================================

/**
 * loadContacts — GET /contacts.php?user_id=X
 *
 * Fetches the user's contacts and stores them in `contactsList`.
 * Then calls populateContactDropdown() to fill the <select> in the
 * "New Loan" form.
 */
async function loadContacts() {
    var res  = await fetch(API_BASE_URL + 'contacts.php?user_id=' + currentUserId);
    var data = await res.json();

    if (data.success) {
        contactsList = data.contacts;
        populateContactDropdown();
    }
}

/**
 * populateContactDropdown — rebuilds the <select> options for contacts.
 *
 * We clear the dropdown first (except the placeholder), then create an
 * <option> for each contact.  option.value = contact_id is what gets
 * sent to the server; option.textContent is what the user sees.
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
 * loadLoans — GET /loans.php?user_id=X
 *
 * The backend returns loans JOINed with contacts (so we get names) and
 * includes a `total_paid` field computed by a SUM subquery. After
 * fetching we render cards and update the summary.
 */
async function loadLoans() {
    var res  = await fetch(API_BASE_URL + 'loans.php?user_id=' + currentUserId);
    var data = await res.json();

    if (data.success) {
        loansList = data.loans;
        renderLoans();
        updateSummary();
    }
}


// ===========================================================================
//  RENDERING
// ===========================================================================

/**
 * renderLoans — splits loansList into Lent / Borrowed and builds HTML.
 *
 * Array methods used (important for viva):
 *   .filter(fn)  — returns a new array with only items where fn is true
 *   .map(fn)     — transforms each item and returns a new array
 *   .join('')     — glues array strings into one big string
 */
function renderLoans() {
    var lent     = loansList.filter(function (l) { return l.loan_type === 'Lent'; });
    var borrowed = loansList.filter(function (l) { return l.loan_type === 'Borrowed'; });

    // --- lent column ---
    var lentBox = document.getElementById('loans-lent');
    if (lent.length === 0) {
        lentBox.innerHTML = '<p class="empty-msg">No loans lent yet. Click "+ New Loan" to start.</p>';
    } else {
        lentBox.innerHTML = lent.map(createLoanCard).join('');
    }

    // --- borrowed column ---
    var borrowedBox = document.getElementById('loans-borrowed');
    if (borrowed.length === 0) {
        borrowedBox.innerHTML = '<p class="empty-msg">No borrowed loans yet. Click "+ New Loan" to start.</p>';
    } else {
        borrowedBox.innerHTML = borrowed.map(createLoanCard).join('');
    }

    // count badges
    document.getElementById('lent-count').textContent     = lent.length;
    document.getElementById('borrowed-count').textContent = borrowed.length;
}


/**
 * createLoanCard — returns the HTML string for one loan card.
 *
 * The card shows: contact name, amount, dates, status badge,
 * a thin progress bar, and action buttons.
 *
 * Derived values (remaining, progress %) are computed here rather
 * than stored in the DB — avoids redundancy.
 *
 * @param {Object} loan
 * @returns {string}
 */
function createLoanCard(loan) {

    var remaining = Math.max(0, loan.amount - loan.total_paid);
    var pct       = Math.min(100, (loan.total_paid / loan.amount) * 100);

    // map status to CSS class
    var badgeClass = 'badge-unpaid';
    if (loan.status === 'Partially Paid') badgeClass = 'badge-partial';
    if (loan.status === 'Settled')        badgeClass = 'badge-settled';

    // card type class controls the left accent stripe colour
    var typeClass = loan.loan_type === 'Lent' ? 'type-lent' : 'type-borrowed';

    // conditional sections
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

    // action buttons — hide "Log Payment" when settled
    var actions;
    if (loan.status !== 'Settled') {
        actions =
            '<button onclick="openRepaymentModal(' + loan.loan_id + ')" class="btn btn-outline btn-sm flex-1">Log Payment</button>' +
            '<button onclick="viewRepaymentHistory(' + loan.loan_id + ')" class="btn btn-ghost btn-sm flex-1">History</button>';
    } else {
        actions =
            '<button onclick="viewRepaymentHistory(' + loan.loan_id + ')" class="btn btn-ghost btn-sm flex-1">View History</button>';
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
 * updateSummary — computes totals and writes them into the summary cards.
 *
 * Uses .reduce() to sum values:
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
//  FORM HANDLERS
// ===========================================================================

/**
 * handleAddContact — POST /contacts.php
 *
 * Creates a new contact, then reloads the contact list (which also
 * refreshes the dropdown in the loan form).
 */
async function handleAddContact(event) {
    event.preventDefault();

    var name  = document.getElementById('contact-name').value.trim();
    var phone = document.getElementById('contact-phone').value.trim();

    if (!name) { showToast('Enter a contact name.', 'error'); return false; }

    try {
        var res  = await fetch(API_BASE_URL + 'contacts.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user_id: currentUserId,
                contact_name: name,
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
 * handleAddLoan — POST /loans.php
 *
 * Creates a new loan record, then reloads the loan list to show it
 * in the correct column.
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
        var res = await fetch(API_BASE_URL + 'loans.php', {
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
            // re-set today
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
 * openRepaymentModal — fills the repayment modal with the selected
 * loan's info (contact name, amount, remaining) and opens it.
 *
 * .find() — searches the cached loansList and returns the first match.
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
 * handleAddRepayment — POST /repayments.php
 *
 * The backend wraps the insert + status-update in a TRANSACTION:
 *   1. INSERT repayment
 *   2. SELECT SUM(amount_paid) for the loan
 *   3. UPDATE loans.status to 'Partially Paid' or 'Settled'
 *
 * We then reload loans so the card reflects the new status/progress.
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
        var res = await fetch(API_BASE_URL + 'repayments.php', {
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
 * viewRepaymentHistory — GET /repayments.php?loan_id=X
 *
 * Fetches all payments for a loan and displays them in a modal.
 */
async function viewRepaymentHistory(loanId) {
    var loan = loansList.find(function (l) { return l.loan_id === loanId; });

    try {
        var res  = await fetch(API_BASE_URL + 'repayments.php?loan_id=' + loanId);
        var data = await res.json();
        var box  = document.getElementById('repayment-history-content');

        if (data.success && data.repayments.length > 0) {
            var html = '';

            // loan summary block
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

            // individual payments
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
//  MODAL HELPERS
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
//  LOGOUT
// ===========================================================================

function handleLogout() {
    localStorage.clear();
    window.location.href = 'index.html';
}


// ===========================================================================
//  UTILITY FUNCTIONS
// ===========================================================================

/**
 * fmtNum — formats a number to 2 decimal places with Indian locale commas.
 *
 * toLocaleString('en-IN') uses the lakh/crore system:
 *   1234567.5 => "12,34,567.50"
 */
function fmtNum(n) {
    return Number(n).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

/** formatDate — turns "2024-01-15" into "Jan 15, 2024" */
function formatDate(str) {
    if (!str) return 'N/A';
    return new Date(str + 'T00:00:00').toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric'
    });
}

/** formatDateTime — turns a timestamp into "Jan 15, 2024, 02:30 PM" */
function formatDateTime(str) {
    if (!str) return 'N/A';
    return new Date(str).toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit'
    });
}

/**
 * escapeHtml — prevents XSS by converting < > & " into safe entities.
 *
 * How: set the string as textContent (auto-escapes), then read it back
 * from innerHTML.
 */
function escapeHtml(str) {
    var d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

/**
 * showToast — small notification that auto-dismisses after 3.5 s.
 *
 * Duplicated from auth.js because dashboard.html loads app.js (not
 * auth.js). In a bigger project you'd factor this into a shared file.
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
