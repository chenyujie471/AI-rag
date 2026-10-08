/**
 * 登录页逻辑。
 *
 *   POST /api/auth/login -> 拿到 JWT 存 localStorage -> 成功动画 -> 跳转 redirectUrl
 *
 * 跳转地址完全由后端决定(app.auth.redirect-url):
 *   - 默认:我们自己的问答页 /faq.html(右上角可切到 /after-sale.html)
 *   - 也能填外部地址(比如 FastGPT 分享页),前端不用改
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'airag_token';
    var USER_KEY = 'airag_user';
    var REMEMBER_KEY = 'airag_remember_username';
    var DEFAULT_REDIRECT = '/faq.html';
    var REDIRECT_DELAY_MS = 1300;
    var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    var $ = function (id) { return document.getElementById(id); };
    var el = {
        card: $('card'),
        form: $('login-form'),
        formView: $('form-view'),
        successView: $('success-view'),
        successTitle: $('success-title'),
        successDesc: $('success-desc'),
        errorBox: $('login-error'),
        errorText: $('login-error-text'),
        button: $('login-button'),
        username: $('username'),
        password: $('password'),
        fieldUsername: $('field-username'),
        fieldPassword: $('field-password'),
        togglePassword: $('toggle-password'),
        remember: $('remember'),
        capsHint: $('caps-hint'),
        forgot: $('forgot-button'),
        toast: $('toast'),

        // 修改密码视图
        pwdView: $('pwd-view'),
        pwdForm: $('pwd-form'),
        pwdUsername: $('pwd-username'),
        oldPassword: $('old-password'),
        newPassword: $('new-password'),
        confirmPassword: $('confirm-password'),
        pwdButton: $('pwd-button'),
        pwdBack: $('pwd-back'),
        pwdError: $('pwd-error'),
        pwdErrorText: $('pwd-error-text'),
        fieldPwdUsername: $('field-pwd-username'),
        fieldOldPassword: $('field-old-password'),
        fieldNewPassword: $('field-new-password'),
        fieldConfirmPassword: $('field-confirm-password')
    };

    var submitting = false;
    var pwdSubmitting = false;

    // ============================================================ 工具

    function storage(action, key, value) {
        try {
            if (action === 'get') { return localStorage.getItem(key); }
            if (action === 'set') { localStorage.setItem(key, value); }
            if (action === 'del') { localStorage.removeItem(key); }
        } catch (e) { /* 隐私模式下忽略 */ }
        return null;
    }

    function showToast(message) {
        el.toast.textContent = message;
        el.toast.hidden = false;
        window.clearTimeout(showToast.timer);
        showToast.timer = window.setTimeout(function () { el.toast.hidden = true; }, 3000);
    }

    function setLoading(on) {
        submitting = on;
        el.button.disabled = on;
        el.button.classList.toggle('is-loading', on);
        el.button.querySelector('.submit-label').textContent = on ? '正在登录' : '点击登录';
    }

    function showError(message, field) {
        el.errorText.textContent = message;
        el.errorBox.hidden = false;
        if (field) { field.classList.add('has-error'); }
        el.card.classList.remove('is-shaking');
        void el.card.offsetWidth;          // 重新触发动画
        el.card.classList.add('is-shaking');
    }

    function clearError() {
        el.errorBox.hidden = true;
        el.fieldUsername.classList.remove('has-error');
        el.fieldPassword.classList.remove('has-error');
    }

    /** 只允许站内路径或 http(s) 地址,防止被配置成 javascript: 之类。 */
    function safeRedirect(url) {
        if (!url || typeof url !== 'string') { return DEFAULT_REDIRECT; }
        var trimmed = url.trim();
        if (/^\/(?!\/)/.test(trimmed) || /^https?:\/\//i.test(trimmed)) { return trimmed; }
        return DEFAULT_REDIRECT;
    }

    function go(url) {
        window.location.replace(safeRedirect(url));
    }

    // ============================================================ 登录

    function submitLogin() {
        if (submitting) { return; }
        clearError();

        var username = el.username.value.trim();
        var password = el.password.value;
        if (!username) {
            showError('请输入账号', el.fieldUsername);
            el.username.focus();
            return;
        }
        if (!password) {
            showError('请输入密码', el.fieldPassword);
            el.password.focus();
            return;
        }

        setLoading(true);
        fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, password: password })
        })
            .then(function (resp) {
                return resp.json().catch(function () { return null; }).then(function (body) {
                    return { status: resp.status, body: body };
                });
            })
            .then(function (result) {
                var body = result.body;
                if (!body) {
                    showError('服务返回异常（HTTP ' + result.status + '）');
                    return;
                }
                if (body.code !== 0) {
                    if (result.status === 429) {
                        showError(body.message || '尝试过于频繁，请稍后再试');
                    } else {
                        showError(body.message || '登录失败', result.status === 401 ? el.fieldPassword : null);
                        if (result.status === 401) { el.password.select(); }
                    }
                    return;
                }
                if (el.remember.checked) {
                    storage('set', REMEMBER_KEY, username);
                } else {
                    storage('del', REMEMBER_KEY);
                }
                storage('set', TOKEN_KEY, body.data.token);
                storage('set', USER_KEY, JSON.stringify(body.data.user || {}));
                onSuccess(body.data);
            })
            .catch(function () {
                showError('网络异常，请确认后端服务已启动');
            })
            .finally(function () {
                setLoading(false);
            });
    }

    function onSuccess(data) {
        var user = data.user || {};
        var name = user.nickname || user.username || '';
        var target = safeRedirect(data.redirectUrl);
        el.successTitle.textContent = name ? '欢迎，' + name : '登录成功';
        el.successDesc.textContent = /^https?:/i.test(target) ? '正在跳转到 ' + target.replace(/^https?:\/\//i, '').split('/')[0] : '正在进入对话…';
        el.formView.hidden = true;
        el.successView.hidden = false;
        window.setTimeout(function () { go(target); }, reduceMotion ? 200 : REDIRECT_DELAY_MS);
    }

    // ============================================================ 修改密码

    /**
     * 两个视图之间切换。修改密码是在"登录之前"用的,所以这里不涉及任何令牌 ——
     * 身份完全由后端核对原密码来确认(POST /api/auth/password)。
     */
    function showPwdView() {
        clearError();
        clearPwdError();
        el.formView.hidden = true;
        el.successView.hidden = true;
        el.pwdView.hidden = false;
        // 账号从登录框带过来,省得再输一遍
        el.pwdUsername.value = el.username.value.trim() || storage('get', REMEMBER_KEY) || '';
        el.oldPassword.value = '';
        el.newPassword.value = '';
        el.confirmPassword.value = '';
        if (el.pwdUsername.value) { el.oldPassword.focus(); } else { el.pwdUsername.focus(); }
    }

    function showLoginView() {
        clearPwdError();
        el.pwdView.hidden = true;
        el.successView.hidden = true;
        el.formView.hidden = false;
        el.password.value = '';
        el.password.focus();
    }

    function showPwdError(message, field) {
        el.pwdErrorText.textContent = message;
        el.pwdError.hidden = false;
        if (field) { field.classList.add('has-error'); }
        el.card.classList.remove('is-shaking');
        void el.card.offsetWidth;
        el.card.classList.add('is-shaking');
    }

    function clearPwdError() {
        el.pwdError.hidden = true;
        el.fieldPwdUsername.classList.remove('has-error');
        el.fieldOldPassword.classList.remove('has-error');
        el.fieldNewPassword.classList.remove('has-error');
        el.fieldConfirmPassword.classList.remove('has-error');
    }

    function setPwdLoading(on) {
        pwdSubmitting = on;
        el.pwdButton.disabled = on;
        el.pwdButton.classList.toggle('is-loading', on);
        el.pwdButton.querySelector('.submit-label').textContent = on ? '正在修改' : '确认修改';
    }

    function submitPasswordChange() {
        if (pwdSubmitting) { return; }
        clearPwdError();

        var username = el.pwdUsername.value.trim();
        var oldPwd = el.oldPassword.value;
        var newPwd = el.newPassword.value;
        var confirmPwd = el.confirmPassword.value;

        // 先在前端把能判断的都判断掉,少一次来回。后端还会再校验一遍(那才是说了算的)。
        if (!username) {
            showPwdError('请输入账号', el.fieldPwdUsername);
            el.pwdUsername.focus();
            return;
        }
        if (!oldPwd) {
            showPwdError('请输入原密码', el.fieldOldPassword);
            el.oldPassword.focus();
            return;
        }
        if (newPwd.length < 6) {
            showPwdError('新密码至少 6 位', el.fieldNewPassword);
            el.newPassword.focus();
            return;
        }
        if (newPwd !== confirmPwd) {
            showPwdError('两次输入的新密码不一致', el.fieldConfirmPassword);
            el.confirmPassword.focus();
            return;
        }
        if (newPwd === oldPwd) {
            showPwdError('新密码不能和原密码相同', el.fieldNewPassword);
            el.newPassword.focus();
            return;
        }

        setPwdLoading(true);
        fetch('/api/auth/password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, oldPassword: oldPwd, newPassword: newPwd })
        })
            .then(function (resp) {
                return resp.json().catch(function () { return null; }).then(function (body) {
                    return { status: resp.status, body: body };
                });
            })
            .then(function (result) {
                var body = result.body;
                if (!body) {
                    showPwdError('服务返回了无法解析的内容（HTTP ' + result.status + '）');
                    return;
                }
                if (body.code !== 0) {
                    if (result.status === 429) {
                        showPwdError(body.message || '尝试过于频繁，请稍后再试');
                    } else if (result.status === 401) {
                        showPwdError('原密码不正确', el.fieldOldPassword);
                        el.oldPassword.select();
                    } else {
                        showPwdError(body.message || '修改失败');
                    }
                    return;
                }
                // 成功:回到登录视图,账号填好、密码清空,让用户用新密码登一次
                el.username.value = username;
                if (el.remember.checked) { storage('set', REMEMBER_KEY, username); }
                showLoginView();
                showToast('密码已修改，请用新密码登录');
            })
            .catch(function () {
                showPwdError('网络异常，请确认后端服务已启动');
            })
            .finally(function () {
                setPwdLoading(false);
            });
    }

    /**
     * 已登录且令牌有效则直接进入,省得每次重新登录。
     *
     * <p>但如果地址里带了 {@code #password}(说明用户是明确冲着"修改密码"来的),
     * 就<b>不要</b>把人弹走 —— 否则已登录的人根本点不到那个入口。</p>
     */
    function redirectIfAlreadyLoggedIn() {
        if (wantsPasswordView()) {
            showPwdView();
            return;
        }
        var token = storage('get', TOKEN_KEY);
        if (!token) { return; }
        fetch('/api/auth/me', { headers: { Authorization: 'Bearer ' + token } })
            .then(function (resp) {
                return resp.json().catch(function () { return null; }).then(function (body) {
                    return { ok: resp.ok, body: body };
                });
            })
            .then(function (r) {
                if (r.ok && r.body && r.body.code === 0) {
                    go(r.body.data && r.body.data.redirectUrl);
                } else {
                    storage('del', TOKEN_KEY);
                    storage('del', USER_KEY);
                }
            })
            .catch(function () { /* 后端没起来时不阻断 */ });
    }

    /** 地址里写了 #password 就表示"我要改密码":/index.html#password */
    function wantsPasswordView() {
        return window.location.hash.replace(/^#/, '').toLowerCase() === 'password';
    }

    // ============================================================ 表单交互

    function togglePassword() {
        var show = el.password.type === 'password';
        el.password.type = show ? 'text' : 'password';
        el.togglePassword.classList.toggle('is-visible', show);
        el.togglePassword.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
        el.password.focus();
    }

    /** 通用的密码显隐:按钮用 data-target 指向要切换的输入框 id(修改密码表单里的三个框共用)。 */
    function togglePasswordField(button) {
        var input = $(button.getAttribute('data-target'));
        if (!input) { return; }
        var show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        button.classList.toggle('is-visible', show);
        button.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
        input.focus();
    }

    function updateCapsLock(event) {
        var on = !!(event.getModifierState && event.getModifierState('CapsLock'));
        el.capsHint.hidden = !on;
    }

    // ============================================================ 初始化

    function init() {
        var remembered = storage('get', REMEMBER_KEY);
        if (remembered) {
            el.username.value = remembered;
            el.remember.checked = true;
            el.password.focus();
        } else {
            el.username.focus();
        }

        el.form.addEventListener('submit', function (e) {
            e.preventDefault();
            submitLogin();
        });
        el.username.addEventListener('input', clearError);
        el.password.addEventListener('input', clearError);
        el.togglePassword.addEventListener('click', togglePassword);
        el.password.addEventListener('keydown', updateCapsLock);
        el.password.addEventListener('keyup', updateCapsLock);
        el.password.addEventListener('blur', function () { el.capsHint.hidden = true; });
        el.forgot.addEventListener('click', showPwdView);

        el.pwdBack.addEventListener('click', showLoginView);
        el.pwdForm.addEventListener('submit', function (e) {
            e.preventDefault();
            submitPasswordChange();
        });
        el.pwdUsername.addEventListener('input', clearPwdError);
        el.oldPassword.addEventListener('input', clearPwdError);
        el.newPassword.addEventListener('input', clearPwdError);
        el.confirmPassword.addEventListener('input', clearPwdError);
        Array.prototype.forEach.call(document.querySelectorAll('.js-pwd-toggle'), function (button) {
            button.addEventListener('click', function () { togglePasswordField(button); });
        });

        redirectIfAlreadyLoggedIn();
    }

    init();
})();
