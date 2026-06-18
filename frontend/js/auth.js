/**
 * ============================================================================
 * ফাইল: auth.js — লগইন ও রেজিস্ট্রেশন লজিক
 * ============================================================================
 * 
 * যা হ্যান্ডেল করে:
 *   ১. Sign In / Create Account ট্যাবের মধ্যে স্যুইচ করা
 *   ২. লগইন — auth-এ POST রিকোয়েস্ট, localStorage-এ সেশন সংরক্ষণ
 *   ৩. রেজিস্ট্রেশন — auth-এ POST রিকোয়েস্ট, তারপর লগইন ট্যাবে রিডাইরেক্ট
 *   ৪. সাফল্য/ত্রুটি ফিডব্যাকের জন্য টোস্ট নোটিফিকেশন
 * 
 * ভাইভার জন্য মূল ধারণা:
 * 
 * fetch() — HTTP রিকোয়েস্ট করার আধুনিক উপায়; একটি Promise রিটার্ন করে।
 * async/await — Promise-এর উপর syntactic sugar; .then() কলব্যাক চেইনের
 *   পরিবর্তে আমাদের অ্যাসিনক্রোনাস কোড লিখতে দেয় যা উপর থেকে নিচে পড়া যায়।
 * localStorage — ব্রাউজারের key-value স্টোর যা পৃষ্ঠা রিলোড করার পরেও থাকে।
 *   আমরা user_id + full_name এখানে রাখি যাতে ড্যাশবোর্ড জানে কে লগইন করেছে।
 *   প্রোডাকশনের জন্য নিরাপদ নয় (যেকোনো JS এটি পড়তে পারে); ডেমোর জন্য ঠিক আছে।
 * event.preventDefault() — ব্রাউজারের ডিফল্ট ফর্ম সাবমিট বন্ধ করে (যা
 *   পৃষ্ঠা রিলোড করত এবং আমাদের JS স্টেট হারিয়ে যেত)।
 * ============================================================================
 */

// ব্যবহারকারী যদি ইতিমধ্যে লগইন করে থাকেন, তাহলে সরাসরি ড্যাশবোর্ডে যান।
// এটি একটি IIFE-তে মোড়ানো যাতে স্ক্রিপ্ট লোড হওয়ার সাথে সাথে এটি চলে
// গ্লোবাল স্কোপে ভেরিয়েবল লিক না করে।
(function checkExistingSession() {
    if (localStorage.getItem('user_id')) {
        window.location.href = 'dashboard.html';
    }
})();


/**
 * switchTab — লগইন এবং রেজিস্টার ফর্মের দৃশ্যমানতা টগল করে।
 *
 * ব্যবহৃত DOM মেথড:
 *   getElementById() — HTML id দিয়ে একটি নির্দিষ্ট এলিমেন্ট ধরুন
 *   .style.display   — CSS ডিসপ্লে প্রপার্টি ('block' = দৃশ্যমান, 'none' = লুকানো)
 *   .classList.add / .remove — স্টাইলিংয়ের জন্য CSS ক্লাস টগল করুন
 *
 * @param {string} tab  হয় 'login' অথবা 'register'
 */
function switchTab(tab) {
    const loginForm    = document.getElementById('form-login');
    const registerForm = document.getElementById('form-register');
    const tabLogin     = document.getElementById('tab-login');
    const tabRegister  = document.getElementById('tab-register');

    if (tab === 'login') {
        loginForm.style.display    = 'block';
        registerForm.style.display = 'none';
        tabLogin.classList.add('active');
        tabRegister.classList.remove('active');
    } else {
        loginForm.style.display    = 'none';
        registerForm.style.display = 'block';
        tabRegister.classList.add('active');
        tabLogin.classList.remove('active');
    }
}


/**
 * handleLogin — লগইন ফর্ম প্রসেস করে।
 *
 * ফ্লো (Flow):
 *  ১. ডিফল্ট ফর্ম সাবমিশন (পৃষ্ঠা রিলোড) প্রতিরোধ করুন
 *  ২. ইনপুট থেকে ইমেইল + পাসওয়ার্ড ধরুন
 *  ৩. action:"login" সহ auth API-তে JSON POST করুন
 *  ৪. সফল হলে → user_id এবং full_name সংরক্ষণ করুন, ড্যাশবোর্ডে রিডাইরেক্ট করুন
 *  ৫. ব্যর্থ হলে → এরর টোস্ট দেখান
 *
 * fetch() অপশন ব্যাখ্যা:
 *   method  — HTTP verb (এখানে POST কারণ আমরা ক্রেডেনশিয়াল পাঠাচ্ছি)
 *   headers — ব্যাকএন্ডকে বলে যে আমরা JSON পাঠাচ্ছি
 *   body    — আসল ডেটা, JSON.stringify() দিয়ে সিরিয়ালাইজ করা
 *
 * @param {Event} event  ফর্মের submit ইভেন্ট
 */
async function handleLogin(event) {
    event.preventDefault();

    const email    = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;

    if (!email || !password) {
        showToast('Please fill in all fields.', 'error');
        return false;
    }

    const btn = document.getElementById('btn-login');
    btn.disabled = true;
    btn.textContent = 'Signing in\u2026';

    try {
        const res = await fetch(API_BASE_URL + 'auth', { // Changed to match Java endpoint
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'login', email, password })
        });

        const data = await res.json();

        if (data.success) {
            // সেশন সংরক্ষণ করুন — ড্যাশবোর্ড লোড হওয়ার সময় এগুলো পড়ে
            localStorage.setItem('user_id', data.user_id);
            localStorage.setItem('full_name', data.full_name);

            showToast('Welcome back, ' + data.full_name + '!', 'success');
            setTimeout(() => { window.location.href = 'dashboard.html'; }, 600);
        } else {
            showToast(data.message || 'Login failed.', 'error');
        }
    } catch (err) {
        // নেটওয়ার্ক-স্তরের ব্যর্থতায় (সার্ভার ডাউন, CORS ব্লক, ইত্যাদি) catch কল হয়
        console.error('Login error:', err);
        showToast('Network error — is the backend running?', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Sign In';
    }

    return false;
}


/**
 * handleRegister — রেজিস্ট্রেশন ফর্ম প্রসেস করে।
 *
 * লগইনের তুলনায় অতিরিক্ত ভ্যালিডেশন:
 *   - পাসওয়ার্ড দৈর্ঘ্য >= ৬
 *   - পাসওয়ার্ড === কনফার্ম
 *
 * সফল হলে আমরা অটো-লগইন করি না; পরিবর্তে আমরা Sign In
 * ট্যাবে স্যুইচ করি যাতে ব্যবহারকারী তাদের নতুন ক্রেডেনশিয়াল দিয়ে সজ্ঞানে লগইন করে।
 *
 * @param {Event} event
 */
async function handleRegister(event) {
    event.preventDefault();

    const fullName = document.getElementById('reg-name').value.trim();
    const email    = document.getElementById('reg-email').value.trim();
    const password = document.getElementById('reg-password').value;
    const confirm  = document.getElementById('reg-confirm').value;

    if (!fullName || !email || !password || !confirm) {
        showToast('Please fill in all fields.', 'error');
        return false;
    }
    if (password.length < 6) {
        showToast('Password must be at least 6 characters.', 'error');
        return false;
    }
    if (password !== confirm) {
        showToast('Passwords do not match.', 'error');
        return false;
    }

    const btn = document.getElementById('btn-register');
    btn.disabled = true;
    btn.textContent = 'Creating account\u2026';

    try {
        const res = await fetch(API_BASE_URL + 'auth', { // Changed to match Java endpoint
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                action: 'register',
                full_name: fullName,
                email,
                password
            })
        });

        const data = await res.json();

        if (data.success) {
            showToast('Account created — please sign in.', 'success');
            document.getElementById('form-register').reset();
            setTimeout(() => switchTab('login'), 800);
        } else {
            showToast(data.message || 'Registration failed.', 'error');
        }
    } catch (err) {
        console.error('Registration error:', err);
        showToast('Network error — is the backend running?', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Create Account';
    }

    return false;
}


/**
 * showToast — একটি ছোট নোটিফিকেশন দেখায় যা স্বয়ংক্রিয়ভাবে বন্ধ হয়ে যায়।
 *
 * আমরা প্রতিবার একটি নতুন DOM এলিমেন্ট তৈরি করি (আগে থেকে থাকা কোনোটি
 * টগল করার পরিবর্তে) যাতে প্রয়োজনে একাধিক টোস্ট স্ট্যাক হতে পারে।
 *
 * @param {string} message  যে টেক্সট দেখাতে হবে
 * @param {string} type     'success' | 'error' | 'info'
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
