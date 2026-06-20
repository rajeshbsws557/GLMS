(function checkExistingSession() {
    if (localStorage.getItem('user_id')) {
        window.location.href = 'dashboard.html';
    }
})();

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
        const res = await fetch(API_BASE_URL + 'auth', { 
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'login', email, password })
        });

        const data = await res.json();

        if (data.success) {

            localStorage.setItem('user_id', data.user_id);
            localStorage.setItem('full_name', data.full_name);

            showToast('Welcome back, ' + data.full_name + '!', 'success');
            setTimeout(() => { window.location.href = 'dashboard.html'; }, 600);
        } else {
            showToast(data.message || 'Login failed.', 'error');
        }
    } catch (err) {

        console.error('Login error:', err);
        showToast('Network error — is the backend running?', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Sign In';
    }

    return false;
}

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
        const res = await fetch(API_BASE_URL + 'auth', { 
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
