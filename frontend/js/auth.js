/**
 * ============================================================================
 * FILE: auth.js — Login & Registration Logic
 * ============================================================================
 * 
 * Handles:
 *   1. Switching between the Sign In / Create Account tabs
 *   2. Login — POST to auth.php, store session in localStorage
 *   3. Registration — POST to auth.php, then redirect to login tab
 *   4. Toast notifications for success/error feedback
 * 
 * KEY CONCEPTS FOR VIVA:
 * 
 * fetch() — modern way to make HTTP requests; returns a Promise.
 * async/await — syntactic sugar over Promises; lets us write asynchronous
 *   code that reads top-to-bottom instead of chaining .then() callbacks.
 * localStorage — browser key-value store that persists across page reloads.
 *   We keep user_id + full_name here so the dashboard knows who's logged in.
 *   NOT secure for production (any JS can read it); fine for a demo.
 * event.preventDefault() — stops the browser's default form submit (which
 *   would reload the page and lose our JS state).
 * ============================================================================
 */

// If the user is already logged in, skip straight to dashboard.
// This is wrapped in an IIFE so it runs immediately on script load
// without leaking variables into the global scope.
(function checkExistingSession() {
    if (localStorage.getItem('user_id')) {
        window.location.href = 'dashboard.html';
    }
})();


/**
 * switchTab — toggles visibility between login and register forms.
 *
 * DOM methods used:
 *   getElementById() — grab a specific element by its HTML id
 *   .style.display   — CSS display property ('block' = visible, 'none' = hidden)
 *   .classList.add / .remove — toggle CSS classes for styling
 *
 * @param {string} tab  Either 'login' or 'register'
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
 * handleLogin — processes the login form.
 *
 * Flow:
 *  1. Prevent default form submission (page reload)
 *  2. Grab email + password from the inputs
 *  3. POST JSON to auth.php with action:"login"
 *  4. On success → store user_id & full_name, redirect to dashboard
 *  5. On failure → show error toast
 *
 * fetch() options explained:
 *   method  — HTTP verb (POST here because we're sending credentials)
 *   headers — tells PHP we're sending JSON so it reads php://input
 *   body    — the actual data, serialised with JSON.stringify()
 *
 * @param {Event} event  The form's submit event
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
        const res = await fetch(API_BASE_URL + 'auth.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'login', email, password })
        });

        const data = await res.json();

        if (data.success) {
            // Persist session — dashboard reads these on load
            localStorage.setItem('user_id', data.user_id);
            localStorage.setItem('full_name', data.full_name);

            showToast('Welcome back, ' + data.full_name + '!', 'success');
            setTimeout(() => { window.location.href = 'dashboard.html'; }, 600);
        } else {
            showToast(data.message || 'Login failed.', 'error');
        }
    } catch (err) {
        // catch fires on network-level failures (server down, CORS blocked, etc.)
        console.error('Login error:', err);
        showToast('Network error — is the backend running?', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Sign In';
    }

    return false;
}


/**
 * handleRegister — processes the registration form.
 *
 * Extra validation vs login:
 *   - password length >= 6
 *   - password === confirm
 *
 * On success we DON'T auto-login; instead we switch to the Sign In
 * tab so the user consciously logs in with their new credentials.
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
        const res = await fetch(API_BASE_URL + 'auth.php', {
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
 * showToast — renders a small notification that auto-dismisses.
 *
 * We create a new DOM element each time (rather than toggling a
 * pre-existing one) so multiple toasts can stack if needed.
 *
 * @param {string} message  Text to display
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
