/**
 * 控制台公共布局(faq / after-sale / logs / profile 四页共用)。
 *
 * 用法:页面 <body data-page="faq"> 里放
 *     <header id="app-top"></header><aside id="app-side"></aside>
 * 然后紧跟着同步引入本脚本 —— 在正文渲染前把顶栏、左侧导航、图标 sprite 填好,不会闪。
 *
 * 同时暴露 window.AIRAG:token / api / toast / 时间格式化 / 登出 等公共方法,
 * 使用日志页、个人信息页直接复用。
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'airag_token';
    var USER_KEY = 'airag_user';

    // ------------------------------------------------------------ 导航定义 --
    // 想加菜单就在这里加一项;group 相同的归到同一个分组标题下
    //
    // adminOnly 的项默认带 hidden,拿到当前用户后由 applyAdminVisibility() 决定要不要露出来:
    // **管理员才看得到"管理后台",普通用户界面上没有这一项**。这只是"不给普通用户添乱",
    // 不是安全措施 —— 真正拦住请求的是后端 AdminInterceptor(403 + code 40303)。
    var NAV = [
        { group: '问答中心', key: 'faq', href: '/faq.html', icon: 'sparkles', label: 'AI 知识问答' },
        { group: '问答中心', key: 'after-sale', href: '/after-sale.html', icon: 'life-buoy', label: '售后知识问答' },
        { group: '个人中心', key: 'logs', href: '/logs.html', icon: 'scroll-text', label: '使用日志' },
        { group: '个人中心', key: 'profile', href: '/profile.html', icon: 'circle-user', label: '个人信息' },
        { group: '系统管理', key: 'admin', href: '/admin.html', icon: 'shield', label: '管理后台', adminOnly: true }
    ];

    // ------------------------------------------------ 图标(Lucide, ISC) --
    var ICONS = {
        'sparkles': '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"/><path d="M20 2v4"/><path d="M22 4h-4"/><circle cx="4" cy="20" r="2"/>',
        'life-buoy': '<circle cx="12" cy="12" r="10"/><path d="m4.93 4.93 4.24 4.24"/><path d="m14.83 9.17 4.24-4.24"/><path d="m14.83 14.83 4.24 4.24"/><path d="m9.17 14.83-4.24 4.24"/><circle cx="12" cy="12" r="4"/>',
        'scroll-text': '<path d="M15 12h-5"/><path d="M15 8h-5"/><path d="M19 17V5a2 2 0 0 0-2-2H4"/><path d="M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3"/>',
        'circle-user': '<path d="M18 20a6 6 0 0 0-12 0"/><circle cx="12" cy="10" r="4"/><circle cx="12" cy="12" r="10"/>',
        'send': '<path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z"/><path d="m21.854 2.147-10.94 10.939"/>',
        'plus': '<path d="M5 12h14"/><path d="M12 5v14"/>',
        'history': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
        'refresh-cw': '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
        'rotate-ccw': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
        'copy': '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
        'x': '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
        'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
        'moon': '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
        'log-out': '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
        'search': '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
        'chevron-right': '<path d="m9 18 6-6-6-6"/>',
        'chevron-left': '<path d="m15 18-6-6 6-6"/>',
        'menu': '<line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="18" y2="18"/>',
        'id-card': '<path d="M16 10h2"/><path d="M16 14h2"/><path d="M6.17 15a3 3 0 0 1 5.66 0"/><circle cx="9" cy="11" r="2"/><rect x="2" y="5" width="20" height="14" rx="2"/>',
        'gauge': '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
        'calendar-clock': '<path d="M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5"/><path d="M16 2v4"/><path d="M8 2v4"/><path d="M3 10h5"/><path d="M17.5 17.5 16 16.3V14"/><circle cx="16" cy="16" r="6"/>',
        'chart-column': '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
        'inbox': '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
        'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
        // --- 管理后台 / 导航入口用到(同样来自 Lucide, ISC) ---
        'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
        'shield-check': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
        'users': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
        'user-round': '<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>',
        'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" x2="19" y1="8" y2="14"/><line x1="22" x2="16" y1="11" y2="11"/>',
        'key-round': '<path d="M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z"/><circle cx="16.5" cy="7.5" r=".5" fill="currentColor"/>',
        'trash-2': '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>',
        'power': '<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.77.04"/>',
        'check': '<path d="M20 6 9 17l-5-5"/>',
        'triangle-alert': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 20h16a2 2 0 0 0 1.73-2"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
        'settings': '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
        'ban': '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
        'info': '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
        'message-square': '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>'
    };

    function buildSprite() {
        var html = '<svg class="icon-sprite" aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">';
        Object.keys(ICONS).forEach(function (name) {
            html += '<symbol id="i-' + name + '" viewBox="0 0 24 24">' + ICONS[name] + '</symbol>';
        });
        return html + '</svg>';
    }

    function icon(name, className) {
        return '<svg class="icon' + (className ? ' ' + className : '') + '" aria-hidden="true"><use href="#i-'
            + name + '"></use></svg>';
    }

    // ------------------------------------------------------------ 公共工具 --

    function token() {
        try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; }
    }

    function clearSession() {
        try {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
            Object.keys(sessionStorage).forEach(function (key) {
                if (key.indexOf('airag_') === 0) { sessionStorage.removeItem(key); }
            });
        } catch (e) { /* 忽略 */ }
    }

    function toast(message) {
        var node = document.getElementById('toast');
        if (!node) {
            node = document.createElement('div');
            node.id = 'toast';
            node.className = 'toast';
            node.setAttribute('role', 'status');
            node.setAttribute('aria-live', 'polite');
            document.body.appendChild(node);
        }
        node.textContent = message;
        node.hidden = false;
        window.clearTimeout(toast.timer);
        toast.timer = window.setTimeout(function () { node.hidden = true; }, 3200);
    }

    var redirecting = false;
    function handleUnauthorized() {
        if (redirecting) { return; }
        redirecting = true;
        clearSession();
        toast('登录已过期,正在跳回登录页…');
        window.setTimeout(function () { window.location.replace('/'); }, 1200);
    }

    /**
     * HTTP 403 + code 40303:当前账号不是管理员(或被禁用)。
     * 直接把人送回问答页 —— 留在管理页也没有任何能用的功能。
     */
    var forbiddenHandled = false;
    function handleAdminOnly(message) {
        if (forbiddenHandled) { return; }
        forbiddenHandled = true;
        toast(message || '需要管理员权限,正在返回问答页…');
        window.setTimeout(function () { window.location.replace('/faq.html'); }, 1600);
    }

    /** GET/POST JSON,统一处理 401 与 {code,message,data} 包装。成功 resolve data,失败 reject Error。 */
    function api(path, options) {
        var opts = options || {};
        var headers = opts.headers || {};
        headers.Authorization = 'Bearer ' + token();
        if (opts.body && !headers['Content-Type']) { headers['Content-Type'] = 'application/json'; }
        return fetch(path, { method: opts.method || 'GET', headers: headers, body: opts.body })
            .then(function (resp) {
                if (resp.status === 401) {
                    handleUnauthorized();
                    throw new Error('登录已过期');
                }
                return resp.json().catch(function () { return null; }).then(function (body) {
                    if (!body) { throw new Error('服务返回异常(HTTP ' + resp.status + ')'); }
                    if (body.code !== 0) { throw new Error(body.message || '请求失败'); }
                    return body.data;
                });
            });
    }

    /**
     * 和 api() 一样发请求,但<b>不</b>把非 0 的 code 直接转成异常 —— 而是把
     * HTTP 状态与业务 code 一起交回来,让调用方能区分 40303(非管理员)之类的特殊情况。
     * 401 仍然照常跳登录页并 reject。
     */
    function apiRaw(path, options) {
        var opts = options || {};
        var headers = opts.headers || {};
        headers.Authorization = 'Bearer ' + token();
        if (opts.body && !headers['Content-Type']) { headers['Content-Type'] = 'application/json'; }
        return fetch(path, { method: opts.method || 'GET', headers: headers, body: opts.body })
            .then(function (resp) {
                if (resp.status === 401) {
                    handleUnauthorized();
                    throw new Error('登录已过期');
                }
                return resp.json().catch(function () { return null; }).then(function (body) {
                    return {
                        status: resp.status,
                        code: body ? body.code : null,
                        message: body ? body.message : null,
                        data: body ? body.data : null
                    };
                });
            });
    }

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    /** 兼容 ISO 字符串与 Jackson 时间数组;withSeconds 决定是否带秒 */
    function formatTime(value, withSeconds) {
        if (!value) { return '—'; }
        var text;
        if (Object.prototype.toString.call(value) === '[object Array]') {
            text = value[0] + '-' + pad(value[1]) + '-' + pad(value[2]) + ' '
                + pad(value[3] || 0) + ':' + pad(value[4] || 0) + ':' + pad(value[5] || 0);
        } else {
            text = String(value).replace('T', ' ');
            if (text.length === 16) { text += ':00'; }
        }
        return text.slice(0, withSeconds ? 19 : 16);
    }

    function formatDuration(ms) {
        if (ms == null) { return '—'; }
        if (ms < 1000) { return ms + ' ms'; }
        return (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + ' s';
    }

    var CHANNELS = {
        'faq': { label: 'AI 知识问答', short: 'AI', tone: 'blue' },
        'after-sale': { label: '售后知识问答', short: '售后', tone: 'teal' },
        // 不是知识库链路,而是「今日总计」那一行(两条链路合计,见 DailyLimitService.TOTAL_CHANNEL)。
        // 它只会出现在今日用量列表里,提问记录里不会有这个值,所以直接放进同一张表最省事。
        'total': { label: '今日总计', short: '总计', tone: 'amber' }
    };
    var STATUSES = {
        'SUCCESS': { label: '成功', tone: 'green' },
        'FAILED': { label: '失败', tone: 'red' },
        'REJECTED': { label: '已拦截', tone: 'amber' }
    };

    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function renderMarkdown(node, text) {
        if (window.marked && window.DOMPurify) {
            node.classList.add('md');
            node.innerHTML = window.DOMPurify.sanitize(window.marked.parse(text || '', { breaks: true, gfm: true }));
            Array.prototype.forEach.call(node.querySelectorAll('a[href]'), function (a) {
                a.setAttribute('target', '_blank');
                a.setAttribute('rel', 'noopener noreferrer');
            });
        } else {
            node.textContent = text || '';
        }
    }

    function logout() {
        fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + token() } })
            .catch(function () { /* 无状态令牌,失败也无所谓 */ })
            .finally(function () {
                clearSession();
                window.location.replace('/');
            });
    }

    function cachedUser() {
        try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; }
    }

    // ------------------------------------------------------------ 渲染骨架 --

    function renderTop(top) {
        top.className = 'app-top';
        top.innerHTML =
            '<button class="icon-btn side-toggle" id="side-toggle" type="button" aria-label="展开菜单">' + icon('menu') + '</button>'
            + '<a class="app-brand" href="/faq.html">'
            + '<span class="app-brand-mark"><img class="brand-logo" src="/assets/logo.png" srcset="/assets/logo.png 1x, /assets/logo@2x.png 2x" alt="logo"></span>'
            + '<span class="app-brand-name">AI 知识库助手</span></a>'
            + '<div class="app-top-right">'
            // 带 data-admin-only 的元素默认 hidden,由 applyAdminVisibility() 按 user.admin 开合 ——
            // 普通用户的界面上不会出现这个入口。真正拦住请求的仍是后端 AdminInterceptor。
            + '<a class="top-link" data-admin-only href="/admin.html" title="管理后台" hidden>'
            + icon('shield') + '<span>管理后台</span></a>'
            // "使用日志"只在左侧导航里留一个入口,顶栏不再重复放一遍
            + '<button class="theme-toggle" id="theme-toggle" type="button" aria-label="切换主题">'
            + icon('sun', 'icon-sun') + icon('moon', 'icon-moon') + '</button>'
            + '<a class="user-chip" href="/profile.html" title="个人信息">'
            + '<span class="user-avatar" id="top-avatar">?</span>'
            + '<span class="user-name" id="top-username">—</span></a>'
            + '<button class="icon-btn" id="logout-button" type="button" title="退出登录" aria-label="退出登录">'
            + icon('log-out') + '</button>'
            + '</div>';
    }

    function renderSide(side, current) {
        side.className = 'app-side';
        side.setAttribute('aria-label', '主导航');
        var html = '<nav class="side-nav">';
        var lastGroup = null;
        NAV.forEach(function (item) {
            // 管理后台这类入口先渲染成 hidden,等拿到当前用户再决定是否显示
            var gate = item.adminOnly ? ' data-admin-only hidden' : '';
            if (item.group !== lastGroup) {
                html += '<div class="side-group"' + gate + '>' + item.group + '</div>';
                lastGroup = item.group;
            }
            var active = item.key === current;
            html += '<a class="side-item' + (active ? ' is-active' : '') + '" href="' + item.href + '"'
                + gate
                + (active ? ' aria-current="page"' : '') + '>'
                + icon(item.icon) + '<span>' + item.label + '</span></a>';
        });
        html += '</nav><div class="side-foot">Made by Shenzhen R&amp;D Center</div>';
        side.innerHTML = html;
    }

    /**
     * 按"是不是管理员"开合所有 [data-admin-only] 元素(顶栏入口 + 左侧导航 + 页面自己的块)。
     * 只是隐藏入口,不做任何安全假设。
     */
    function applyAdminVisibility(isAdmin) {
        var nodes = document.querySelectorAll('[data-admin-only]');
        Array.prototype.forEach.call(nodes, function (node) {
            node.hidden = !isAdmin;
        });
    }

    function paintUser(user) {
        if (!user) { return; }
        var name = user.nickname || user.username || '已登录';
        var nameNode = document.getElementById('top-username');
        var avatar = document.getElementById('top-avatar');
        if (nameNode) { nameNode.textContent = name; }
        if (avatar) { avatar.textContent = name.slice(0, 1).toUpperCase(); }
        applyAdminVisibility(user.admin === true);
    }

    function bindShell() {
        var body = document.body;
        var toggle = document.getElementById('side-toggle');
        var mask = document.createElement('div');
        mask.className = 'side-mask';
        body.appendChild(mask);
        function close() { body.classList.remove('side-open'); }
        if (toggle) {
            toggle.addEventListener('click', function () { body.classList.toggle('side-open'); });
        }
        mask.addEventListener('click', close);
        var logoutBtn = document.getElementById('logout-button');
        if (logoutBtn) { logoutBtn.addEventListener('click', logout); }
    }

    // ------------------------------------------------------------ 启动 --

    if (!token()) {
        window.location.replace('/');
        return;
    }

    document.body.insertAdjacentHTML('afterbegin', buildSprite());
    var current = document.body.getAttribute('data-page') || '';
    var top = document.getElementById('app-top');
    var side = document.getElementById('app-side');
    if (top) { renderTop(top); }
    if (side) { renderSide(side, current); }
    bindShell();
    paintUser(cachedUser());

    // 刷一次当前用户,顺便校验令牌是否还有效;结果给页面复用,避免重复请求
    var mePromise = api('/api/auth/me').then(function (data) {
        if (data && data.user) {
            localStorage.setItem(USER_KEY, JSON.stringify(data.user));
            paintUser(data.user);
        }
        return data;
    });
    mePromise.catch(function () { /* 页面自己会处理 */ });

    window.AIRAG = {
        icon: icon,
        token: token,
        api: api,
        apiRaw: apiRaw,
        me: function (refresh) {
            if (refresh) {
                mePromise = api('/api/auth/me');
            }
            return mePromise;
        },
        cachedUser: cachedUser,
        toast: toast,
        logout: logout,
        clearSession: clearSession,
        handleUnauthorized: handleUnauthorized,
        handleAdminOnly: handleAdminOnly,
        applyAdminVisibility: applyAdminVisibility,
        formatTime: formatTime,
        formatDuration: formatDuration,
        escapeHtml: escapeHtml,
        renderMarkdown: renderMarkdown,
        channels: CHANNELS,
        statuses: STATUSES
    };
})();
