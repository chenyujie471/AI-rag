/**
 * 管理后台逻辑(admin.html)。
 *
 * 两个 Tab:
 *   1. 用户管理 —— 搜索 / 角色筛选 / 新建 / 启停 / 改角色 / 重置密码 / 清零已用次数 /
 *      改每日上限 / 删除,带分页。
 *   2. 全站记录 —— 按用户、链路、状态、关键字、时间范围筛选,点击行展开完整问答,带分页。
 *
 * 约定与安全:
 *   - 公共能力(api / 时间格式化 / 标签配色 / 图标 / Markdown 渲染)全部来自 layout.js 的 window.AIRAG,
 *     本文件不重复实现,也不自己拼颜色。
 *   - 所有来自后端的用户数据(账号、昵称、问题、回答、错误信息)一律用 textContent 写入 DOM;
 *     只有回答走 A.renderMarkdown(内部是 DOMPurify.sanitize(marked.parse(...)))。不使用 innerHTML 拼数据。
 *   - 前端只负责"藏入口",权限由后端 AdminInterceptor 保证:HTTP 403 + code 40303 时
 *     提示并把人送回 /faq.html。
 */
(function () {
    'use strict';

    var A = window.AIRAG;
    if (!A) { return; }

    var PAGE_SIZE = 20;
    var MAX_USER_OPTIONS = 100;   // 后端 /api/admin/users 的 size 上限就是 100
    var SVG_NS = 'http://www.w3.org/2000/svg';

    var $ = function (id) { return document.getElementById(id); };

    // ------------------------------------------------------------------ 元素

    var el = {
        loading: $('admin-loading'),
        denied: $('admin-denied'),
        deniedDesc: $('denied-desc'),
        body: $('admin-body'),

        ov: {
            userCount: $('ov-user-count'), userSub: $('ov-user-sub'),
            adminCount: $('ov-admin-count'), adminSub: $('ov-admin-sub'),
            disabledCount: $('ov-disabled-count'), disabledSub: $('ov-disabled-sub'),
            todayCount: $('ov-today-count'), todaySub: $('ov-today-sub'),
            allCount: $('ov-all-count'), allSub: $('ov-all-sub')
        },

        tabButtons: [$('tab-users'), $('tab-logs')],
        panes: { users: $('pane-users'), logs: $('pane-logs') },

        newUser: $('btn-new-user'),
        refresh: $('btn-refresh'),

        userForm: $('user-filters'),
        uKeyword: $('u-keyword'),
        uRole: $('u-role'),
        uReset: $('u-reset'),
        userBody: $('user-body'),
        userPager: $('user-pager'),

        logForm: $('log-filters'),
        lUser: $('l-user'),
        lStart: $('l-start'),
        lEnd: $('l-end'),
        lKeyword: $('l-keyword'),
        lType: $('l-type'),
        lStatus: $('l-status'),
        lReset: $('l-reset'),
        logHint: $('log-hint'),
        logHintText: $('log-hint-text'),
        logSummary: $('log-summary'),
        logBody: $('log-body'),
        logPager: $('log-pager'),

        modal: $('modal'),
        modalMask: $('modal-mask'),
        modalCard: $('modal-card'),
        modalHead: $('modal-head'),
        modalIcon: $('modal-icon'),
        modalTitle: $('modal-title'),
        modalBody: $('modal-body'),
        modalFoot: $('modal-foot'),
        modalClose: $('modal-close')
    };

    var state = {
        tab: 'users',
        me: null,
        users: { page: 1, total: 0, items: [], loading: false },
        logs: { page: 1, total: 0, items: [], loading: false },
        userOptions: []
    };

    // 弹窗内的错误/警示块(每次打开弹窗重建)
    var modalNotice = null;
    var modalNoticeText = null;
    var modalButtons = [];

    // ============================================================== 请求封装

    /**
     * 和 A.api 一样发请求,但能拿到 HTTP 状态与业务 code,以便单独处理 40303(非管理员)。
     * 403 + 40303 时:显示拒绝页 + 提示 + 送回问答页,并 reject 阻断后续逻辑。
     */
    function call(path, options) {
        return A.apiRaw(path, options).then(function (res) {
            if (res.status === 403 && res.code === 40303) {
                showDenied(res.message);
                throw new Error(res.message || '需要管理员权限');
            }
            if (res.code !== 0) {
                throw new Error(res.message || ('请求失败(HTTP ' + res.status + ')'));
            }
            return res.data;
        });
    }

    function getJson(path) {
        return call(path);
    }

    function sendJson(method, path, body) {
        return call(path, {
            method: method,
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    }

    // ============================================================== DOM 工具

    /** 用 createElementNS 造一个 <svg class="icon"><use href="#i-xxx"></use></svg> */
    function iconEl(name) {
        var svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('class', 'icon');
        svg.setAttribute('aria-hidden', 'true');
        var use = document.createElementNS(SVG_NS, 'use');
        use.setAttribute('href', '#i-' + name);
        svg.appendChild(use);
        return svg;
    }

    /** 造元素:tag + class + 文本。文本一律走 textContent。 */
    function h(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined && text !== null) { node.textContent = String(text); }
        return node;
    }

    /** 造一个 <td>,child 可以是元素节点或纯文本 */
    function td(className, child) {
        var node = h('td', className);
        if (child && typeof child === 'object' && child.nodeType === 1) {
            node.appendChild(child);
        } else if (child !== undefined && child !== null) {
            node.textContent = String(child);
        }
        return node;
    }

    /** 造一个带图标的文本块(如"问题"小标题) */
    function labelled(className, iconName, text) {
        var node = h('div', className);
        if (iconName) { node.appendChild(iconEl(iconName)); }
        node.appendChild(document.createTextNode(text));
        return node;
    }

    function clear(node) {
        while (node.firstChild) { node.removeChild(node.firstChild); }
    }

    function tagEl(text, tone) {
        return h('span', 'tag tag-' + (tone || 'gray'), text);
    }

    function plain(text, max) {
        var value = String(text === undefined || text === null ? '' : text).replace(/\s+/g, ' ').trim();
        return value.length > max ? value.slice(0, max) + '…' : value;
    }

    // ============================================================== 格式化

    var ROLE_TEXT = { ADMIN: '管理员', USER: '普通用户' };

    function displayName(row) {
        return row.nickname || row.username || ('#' + row.id);
    }

    function userLabel(row) {
        var name = displayName(row);
        return name === row.username ? name : name + '(' + row.username + ')';
    }

    function roleTag(role) {
        return tagEl(ROLE_TEXT[role] || role || '—', role === 'ADMIN' ? 'teal' : 'gray');
    }

    function stateTag(status) {
        return status === 1 ? tagEl('启用', 'green') : tagEl('已禁用', 'red');
    }

    function channelLabel(channel) {
        var meta = A.channels[channel];
        return meta ? meta.label : (channel || '—');
    }

    function channelTag(channel) {
        var meta = A.channels[channel] || { label: channel || '—', tone: 'gray' };
        return tagEl(meta.label, meta.tone);
    }

    function statusTag(status) {
        var meta = A.statuses[status] || { label: status || '—', tone: 'gray' };
        return tagEl(meta.label, meta.tone);
    }

    function durationTone(ms) {
        if (ms === null || ms === undefined) { return 'gray'; }
        return ms >= 20000 ? 'red' : ms >= 8000 ? 'amber' : 'green';
    }

    /** 每日上限:null = 跟随全局,-1 = 不限,>= 0 = 具体次数 */
    function dailyText(value) {
        if (value === null || value === undefined) { return '跟随全局'; }
        if (value === -1) { return '不限'; }
        return value + ' 次';
    }

    // ============================================================== 权限拒绝

    function showDenied(message) {
        el.loading.hidden = true;
        el.body.hidden = true;
        el.deniedDesc.textContent = (message || '当前账号不是管理员')
            + '。正在返回问答页…';
        el.denied.hidden = false;
        // 统一提示 + 1.6 秒后跳 /faq.html
        A.handleAdminOnly(message);
    }

    // ============================================================== 概览卡片

    function setOvValue(node, value, unit) {
        clear(node);
        node.appendChild(document.createTextNode(String(value)));
        if (unit) { node.appendChild(h('small', null, unit)); }
    }

    function sumOf(list, key) {
        var total = 0;
        (list || []).forEach(function (item) { total += Number(item[key] || 0); });
        return total;
    }

    function breakdown(list) {
        if (!list || !list.length) { return '暂无数据'; }
        return list.map(function (item) {
            var meta = A.channels[item.channel] || { short: item.channel };
            return meta.short + ' ' + (item.total || 0);
        }).join(' · ');
    }

    function loadOverview() {
        return getJson('/api/admin/overview').then(function (data) {
            var today = data.today || [];
            var all = data.allTime || [];
            setOvValue(el.ov.userCount, data.userCount || 0);
            el.ov.userSub.textContent = '注册账号总数';
            setOvValue(el.ov.adminCount, data.adminCount || 0);
            el.ov.adminSub.textContent = '可进入管理后台';
            setOvValue(el.ov.disabledCount, data.disabledCount || 0);
            el.ov.disabledSub.textContent = '已停用的账号';
            setOvValue(el.ov.todayCount, sumOf(today, 'total'), '次');
            el.ov.todaySub.textContent = breakdown(today);
            setOvValue(el.ov.allCount, sumOf(all, 'total'), '次');
            el.ov.allSub.textContent = breakdown(all);
        }).catch(function () {
            // 概览失败不阻断页面;40303 已经在 call() 里处理过了
            setOvValue(el.ov.userCount, '—');
            el.ov.userSub.textContent = '加载失败';
            setOvValue(el.ov.adminCount, '—');
            el.ov.adminSub.textContent = '加载失败';
            setOvValue(el.ov.disabledCount, '—');
            el.ov.disabledSub.textContent = '加载失败';
            setOvValue(el.ov.todayCount, '—');
            el.ov.todaySub.textContent = '加载失败';
            setOvValue(el.ov.allCount, '—');
            el.ov.allSub.textContent = '加载失败';
        });
    }

    // ============================================================== 用户管理

    function setTableMessage(body, colspan, text, isError) {
        clear(body);
        var tr = h('tr');
        var cell = h('td', 't-empty' + (isError ? ' is-error' : ''));
        cell.colSpan = colspan;
        if (!isError) { cell.appendChild(iconEl('inbox')); }
        cell.appendChild(h('div', null, text));
        tr.appendChild(cell);
        body.appendChild(tr);
    }

    function loadUsers(page) {
        if (state.users.loading) { return; }
        state.users.loading = true;
        state.users.page = Math.max(1, page || 1);

        var params = new URLSearchParams();
        var keyword = el.uKeyword.value.trim();
        if (keyword) { params.set('keyword', keyword); }
        if (el.uRole.value) { params.set('role', el.uRole.value); }
        params.set('page', state.users.page);
        params.set('size', PAGE_SIZE);

        setTableMessage(el.userBody, 10, '加载中…');

        return getJson('/api/admin/users?' + params.toString())
            .then(function (data) {
                state.users.total = data.total || 0;
                state.users.items = data.items || [];
                renderUsers();
                renderPager(el.userPager, state.users.page, state.users.total, loadUsers);
            })
            .catch(function (err) {
                setTableMessage(el.userBody, 10, err.message || '加载失败', true);
                clear(el.userPager);
            })
            .finally(function () { state.users.loading = false; });
    }

    function renderUsers() {
        clear(el.userBody);
        if (!state.users.items.length) {
            setTableMessage(el.userBody, 10, '没有符合条件的用户');
            return;
        }
        state.users.items.forEach(function (row) {
            el.userBody.appendChild(buildUserRow(row));
        });
    }

    function buildUserRow(row) {
        var isSelf = !!(state.me && state.me.id === row.id);
        var tr = h('tr', isSelf ? 'is-self' : null);

        tr.appendChild(td('c-id', row.id));

        // 账号
        var userCell = h('td', 'c-user');
        userCell.appendChild(h('div', 'cell-main', row.username));
        if (isSelf) { userCell.appendChild(h('span', 'self-mark', '当前账号')); }
        tr.appendChild(userCell);

        tr.appendChild(td('c-nick', row.nickname || '—'));
        tr.appendChild(td('c-role', roleTag(row.role)));
        tr.appendChild(td('c-state', stateTag(row.status)));

        // 今日用量的主行只报次数 —— 「每日总量」默认不限,配一个分母反而会让人
        // 以为还有个隐藏数字在管着(之前就是这么误解的)
        var usedTotal = (row.todayFaq || 0) + (row.todayAfterSale || 0);
        var todayCell = h('td', 'c-today');
        todayCell.appendChild(h('div', 'cell-main', usedTotal + ' 次'));
        todayCell.appendChild(h('div', 'cell-sub',
            '问答 ' + (row.todayFaq || 0) + ' · 售后 ' + (row.todayAfterSale || 0)));
        tr.appendChild(todayCell);

        var dailyCell = h('td', 'c-daily');
        // 总量只在"确实给这个账号单独设过"时才显示一行;没设就是没有总量限制
        if (row.dailyLimitTotal !== null && row.dailyLimitTotal !== undefined) {
            dailyCell.appendChild(h('div', null, '总量 ' + dailyText(row.dailyLimitTotal)));
        }
        dailyCell.appendChild(h('div', row.dailyLimitTotal === null || row.dailyLimitTotal === undefined ? null : 'cell-sub',
            '问答 ' + dailyText(row.dailyLimitFaq) + ' · 售后 ' + dailyText(row.dailyLimitAfterSale)));
        tr.appendChild(dailyCell);

        tr.appendChild(td('c-login', A.formatTime(row.lastLoginAt)));

        var opsCell = h('td', 'c-ops');
        opsCell.appendChild(buildOps(row, isSelf));
        tr.appendChild(opsCell);

        return tr;
    }

    function opButton(iconName, title, extraClass, onClick) {
        var btn = h('button', 'op-btn' + (extraClass ? ' ' + extraClass : ''));
        btn.type = 'button';
        btn.title = title;
        btn.setAttribute('aria-label', title);
        btn.appendChild(iconEl(iconName));
        btn.addEventListener('click', onClick);
        return btn;
    }

    function buildOps(row, isSelf) {
        var wrap = h('div', 'row-ops');
        var enabled = row.status === 1;

        // 启用 / 禁用(自己的账号不能禁用:后端也会拒绝,这里直接置灰更直观)
        var toggle = opButton(
            enabled ? 'ban' : 'power',
            isSelf ? '不能禁用自己的账号' : (enabled ? '禁用账号' : '启用账号'),
            enabled ? 'is-danger' : '',
            function () {
                if (enabled) {
                    confirmAction({
                        title: '禁用账号',
                        icon: 'ban',
                        danger: true,
                        message: '确定要禁用【' + displayName(row) + '】吗?'
                            + '禁用后该账号将无法登录,也无法使用问答。',
                        confirmText: '禁用',
                        action: function () {
                            return sendJson('PUT', '/api/admin/users/' + row.id, { status: 0 });
                        },
                        successMessage: '已禁用'
                    });
                } else {
                    confirmAction({
                        title: '启用账号',
                        icon: 'power',
                        danger: false,
                        message: '确定要启用【' + displayName(row) + '】吗?启用后该账号可以立即登录使用。',
                        confirmText: '启用',
                        action: function () {
                            return sendJson('PUT', '/api/admin/users/' + row.id, { status: 1 });
                        },
                        successMessage: '已启用'
                    });
                }
            });
        if (isSelf) { toggle.disabled = true; }
        wrap.appendChild(toggle);

        // 改角色
        wrap.appendChild(opButton('user-round', '修改角色', '', function () { openRoleModal(row); }));

        // 重置密码
        wrap.appendChild(opButton('key-round', '重置密码', '', function () { openPasswordModal(row); }));

        // 清零已用次数
        wrap.appendChild(opButton('rotate-ccw', '清零已用次数', '', function () {
            confirmAction({
                title: '清零已用次数',
                icon: 'rotate-ccw',
                danger: false,
                message: '把【' + displayName(row) + '】的"累计已用次数"归零。'
                    + '这只是个记账数字,不影响每天的可用次数(那个看「每日上限」)。'
                    + '当前已用 ' + (row.quotaUsed || 0) + ' 次。',
                confirmText: '清零',
                action: function () {
                    return sendJson('POST', '/api/admin/users/' + row.id + '/quota/reset');
                },
                successMessage: '已清零已用次数'
            });
        }));

        // 改每日上限
        wrap.appendChild(opButton('settings', '设置每日上限', '', function () { openDailyModal(row); }));

        // 删除
        var remove = opButton('trash-2', isSelf ? '不能删除自己的账号' : '删除账号', 'is-danger', function () {
            confirmAction({
                title: '删除账号',
                icon: 'trash-2',
                danger: true,
                message: '确定要删除【' + displayName(row) + '】吗?账号会被删除且无法恢复,'
                    + '他的问答记录与使用日志会保留。',
                confirmText: '删除',
                action: function () {
                    return sendJson('DELETE', '/api/admin/users/' + row.id);
                },
                successMessage: '账号已删除'
            });
        });
        if (isSelf) { remove.disabled = true; }
        wrap.appendChild(remove);

        return wrap;
    }

    /** 用户写操作成功后统一刷新:列表 + 概览(人数会变) */
    function refreshAfterUserChange() {
        loadUsers(state.users.page);
        loadOverview();
        loadUserOptions();
    }

    // ============================================================== 分页器

    function pagerButton(iconName, disabled, onClick) {
        var btn = h('button', 'pg');
        btn.type = 'button';
        btn.disabled = !!disabled;
        btn.appendChild(iconEl(iconName));
        btn.addEventListener('click', onClick);
        return btn;
    }

    function renderPager(container, page, total, go) {
        clear(container);
        var pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
        var info = '共 ' + total + ' 条';
        if (pages > 1) { info += ' · 第 ' + page + ' / ' + pages + ' 页'; }
        container.appendChild(h('span', 'pager-info', info));

        container.appendChild(pagerButton('chevron-left', page <= 1, function () { go(page - 1); }));

        var from = Math.max(1, page - 2);
        var to = Math.min(pages, from + 4);
        from = Math.max(1, to - 4);
        for (var i = from; i <= to; i++) {
            (function (target) {
                var btn = h('button', 'pg' + (target === page ? ' is-active' : ''), target);
                btn.type = 'button';
                btn.addEventListener('click', function () {
                    if (target !== page) { go(target); }
                });
                container.appendChild(btn);
            })(i);
        }

        container.appendChild(pagerButton('chevron-right', page >= pages, function () { go(page + 1); }));
    }

    // ============================================================== 全站记录

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    /** <input type="datetime-local"> 需要的是本地时间,不能用 toISOString()(那是 UTC) */
    function localInput(date) {
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
            + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
    }

    function defaultLogRange() {
        var now = new Date();
        el.lStart.value = localInput(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6, 0, 0, 0));
        el.lEnd.value = localInput(new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59));
    }

    function loadLogs(page) {
        if (state.logs.loading) { return; }
        state.logs.loading = true;
        state.logs.page = Math.max(1, page || 1);

        var params = new URLSearchParams();
        if (el.lUser.value) { params.set('userId', el.lUser.value); }
        if (el.lKeyword.value.trim()) { params.set('keyword', el.lKeyword.value.trim()); }
        if (el.lStart.value) { params.set('start', el.lStart.value); }
        if (el.lEnd.value) { params.set('end', el.lEnd.value); }
        if (el.lType.value) { params.set('type', el.lType.value); }
        if (el.lStatus.value) { params.set('status', el.lStatus.value); }
        params.set('page', state.logs.page);
        params.set('size', PAGE_SIZE);

        renderLogHint();
        setTableMessage(el.logBody, 6, '加载中…');

        return getJson('/api/admin/logs?' + params.toString())
            .then(function (data) {
                state.logs.total = data.total || 0;
                state.logs.items = data.items || [];
                renderLogs();
                renderPager(el.logPager, state.logs.page, state.logs.total, loadLogs);
            })
            .catch(function (err) {
                setTableMessage(el.logBody, 6, err.message || '加载失败', true);
                clear(el.logPager);
            })
            .finally(function () { state.logs.loading = false; });
    }

    function renderLogs() {
        clear(el.logBody);
        if (!state.logs.items.length) {
            setTableMessage(el.logBody, 6, '没有符合条件的记录');
            return;
        }
        state.logs.items.forEach(function (row, index) {
            el.logBody.appendChild(buildLogRow(row, index));
        });
    }

    function buildLogRow(row, index) {
        var tr = h('tr');
        tr.tabIndex = 0;
        tr.dataset.index = String(index);

        var timeCell = h('td', 'c-time');
        var caret = h('span', 'caret');
        caret.appendChild(iconEl('chevron-right'));
        timeCell.appendChild(caret);
        timeCell.appendChild(document.createTextNode(A.formatTime(row.createdAt, true)));
        tr.appendChild(timeCell);

        var accountCell = h('td', 'c-account');
        var account = h('div', 'clip', plain(row.username || ('#' + row.userId), 24));
        account.title = row.username || '';
        accountCell.appendChild(account);
        tr.appendChild(accountCell);

        tr.appendChild(td('c-type', channelTag(row.channel)));
        tr.appendChild(td('c-status', statusTag(row.status)));
        tr.appendChild(td('c-dur', tagEl(A.formatDuration(row.durationMs), durationTone(row.durationMs))));

        var questionCell = h('td', 'c-q');
        var clip = h('div', 'clip', plain(row.question, 80));
        clip.title = row.question || '';
        questionCell.appendChild(clip);
        tr.appendChild(questionCell);

        return tr;
    }

    /** 一次只展开一行;再点同一行收起 */
    function toggleLogRow(tr) {
        var next = tr.nextElementSibling;
        if (next && next.classList.contains('row-expand')) {
            next.remove();
            tr.classList.remove('is-open');
            return;
        }
        var opened = el.logBody.querySelector('tr.row-expand');
        if (opened) {
            var prev = opened.previousElementSibling;
            if (prev) { prev.classList.remove('is-open'); }
            opened.remove();
        }
        var row = state.logs.items[Number(tr.dataset.index)];
        if (!row) { return; }

        var detail = h('tr', 'row-expand');
        var cell = h('td');
        cell.colSpan = 6;
        cell.appendChild(buildExpand(row));
        detail.appendChild(cell);
        tr.parentNode.insertBefore(detail, tr.nextSibling);
        tr.classList.add('is-open');
    }

    function expandBlock(label, iconName, content) {
        var wrap = h('div', 'expand-block');
        wrap.appendChild(labelled('expand-label', iconName, label));
        wrap.appendChild(content);
        return wrap;
    }

    function buildExpand(row) {
        var wrap = h('div', 'expand-inner');

        wrap.appendChild(expandBlock('问题', 'message-square', h('div', 'expand-q', row.question || '—')));

        if (row.errorMsg) {
            wrap.appendChild(expandBlock('失败原因', 'triangle-alert', h('div', 'expand-err', row.errorMsg)));
        }

        var answer = h('div', 'expand-a');
        if (row.answer) {
            // 回答来自大模型,必须先 sanitize 再插入
            A.renderMarkdown(answer, row.answer);
        } else {
            answer.classList.add('is-muted');
            answer.textContent = '(无回答)';
        }
        wrap.appendChild(expandBlock('回答', 'sparkles', answer));

        wrap.appendChild(h('div', 'expand-meta',
            '记录 #' + row.id + ' · 链路 ' + channelLabel(row.channel)
            + ' · 会话 ' + (row.sessionId || '—')
            + ' · 扣减 ' + (row.costQuota || 0) + ' 次'));

        return wrap;
    }

    /**
     * 说明条:选中某个用户筛选时,在这里点明当前看的是谁
     * (表格里已经有「账号」列了,这里只是把筛选状态写得明确一点)。
     */
    function renderLogHint() {
        var userId = el.lUser.value;
        clear(el.logHintText);
        if (!userId) {
            el.logHint.hidden = true;
            return;
        }
        var picked = null;
        state.userOptions.forEach(function (item) {
            if (String(item.id) === String(userId)) { picked = item; }
        });
        el.logHint.hidden = false;
        if (picked) {
            el.logHintText.appendChild(document.createTextNode('当前只看：'));
            el.logHintText.appendChild(h('b', null, userLabel(picked)));
            el.logHintText.appendChild(document.createTextNode(' 的记录。'));
        } else {
            el.logHintText.appendChild(document.createTextNode('当前只看所选用户的记录。'));
        }
    }

    function addSum(label, value, unit, tone) {
        var item = h('div', 'sum-item' + (tone ? ' is-' + tone : ''));
        item.appendChild(h('span', null, label));
        var strong = h('strong', null, value);
        if (unit) { strong.appendChild(h('small', null, unit)); }
        item.appendChild(strong);
        el.logSummary.appendChild(item);
    }

    function loadLogStats() {
        var params = new URLSearchParams();
        if (el.lUser.value) { params.set('userId', el.lUser.value); }
        var query = params.toString();
        return getJson('/api/admin/logs/stats' + (query ? '?' + query : ''))
            .then(function (stats) {
                var faq = stats['faq'] || {};
                var sale = stats['after-sale'] || {};
                clear(el.logSummary);
                addSum('记录总数', (faq.total || 0) + (sale.total || 0), '条');
                addSum('AI 问答', faq.total || 0, '条', 'blue');
                addSum('售后问答', sale.total || 0, '条', 'teal');
                addSum('成功', (faq.success || 0) + (sale.success || 0), '条', 'green');
                addSum('失败 / 拦截',
                    (faq.failed || 0) + (sale.failed || 0) + (faq.rejected || 0) + (sale.rejected || 0),
                    '条', 'red');
            })
            .catch(function () { clear(el.logSummary); });
    }

    /** 记录页的"用户"下拉。后端 size 上限 100,超过就说明只列了前 100 个。 */
    function loadUserOptions() {
        return getJson('/api/admin/users?page=1&size=' + MAX_USER_OPTIONS)
            .then(function (data) {
                state.userOptions = data.items || [];
                var kept = el.lUser.value;

                clear(el.lUser);
                el.lUser.appendChild(new Option('全部用户', ''));
                state.userOptions.forEach(function (item) {
                    el.lUser.appendChild(new Option(userLabel(item), String(item.id)));
                });
                if ((data.total || 0) > state.userOptions.length) {
                    var more = new Option('（仅列出前 ' + state.userOptions.length
                        + ' 个用户，共 ' + data.total + ' 个）', '');
                    more.disabled = true;
                    el.lUser.appendChild(more);
                }
                // 尽量保持原来选中的用户
                if (kept && el.lUser.querySelector('option[value="' + kept + '"]')) {
                    el.lUser.value = kept;
                } else {
                    el.lUser.value = '';
                }
            })
            .catch(function () { /* 拉不到就只留"全部用户",不影响记录查询 */ });
    }

    // ============================================================== 弹窗基础

    function openModal(opts) {
        clear(el.modalBody);
        clear(el.modalFoot);
        modalButtons = [];

        el.modalTitle.textContent = opts.title || '';
        el.modalCard.classList.toggle('is-wide', !!opts.wide);
        el.modalHead.classList.toggle('is-danger', !!opts.danger);
        var use = el.modalIcon.querySelector('use');
        if (use) { use.setAttribute('href', '#i-' + (opts.icon || 'shield')); }

        // 统一的通知块(错误 / 警示),默认隐藏
        modalNotice = h('div', 'modal-alert');
        modalNotice.hidden = true;
        modalNotice.appendChild(iconEl('triangle-alert'));
        modalNoticeText = h('span');
        modalNotice.appendChild(modalNoticeText);
        el.modalBody.appendChild(modalNotice);

        if (typeof opts.build === 'function') { opts.build(el.modalBody); }

        (opts.buttons || []).forEach(function (spec) {
            var btn = h('button', spec.className || 'btn btn-ghost btn-sm', spec.text);
            btn.type = 'button';
            btn.addEventListener('click', function () {
                if (typeof spec.onClick !== 'function') { closeModal(); return; }
                var result = spec.onClick(btn);
                if (!result || typeof result.then !== 'function') { return; }
                setModalBusy(true);
                result.then(function (message) {
                    setModalBusy(false);
                    closeModal();
                    if (message) { A.toast(message); }
                    if (typeof opts.afterChange === 'function') { opts.afterChange(); }
                }).catch(function (err) {
                    setModalBusy(false);
                    showModalNotice((err && err.message) || '操作失败,请稍后再试');
                });
            });
            modalButtons.push(btn);
            el.modalFoot.appendChild(btn);
        });

        el.modal.hidden = false;
        el.modalMask.hidden = false;

        var focus = el.modalBody.querySelector('input, select, textarea');
        if (focus && focus.focus) { focus.focus(); }
    }

    function closeModal() {
        el.modal.hidden = true;
        el.modalMask.hidden = true;
        clear(el.modalBody);
        clear(el.modalFoot);
        modalButtons = [];
        modalNotice = null;
        modalNoticeText = null;
    }

    function modalOpen() {
        return !el.modal.hidden;
    }

    function setModalBusy(busy) {
        modalButtons.forEach(function (btn) { btn.disabled = !!busy; });
        el.modalClose.disabled = !!busy;
    }

    function showModalNotice(message, tone) {
        if (!modalNotice) { return; }
        modalNoticeText.textContent = message;
        modalNotice.className = 'modal-alert' + (tone === 'warn' ? ' is-warn' : '');
        modalNotice.hidden = false;
    }

    /** 纯二次确认弹窗(删除 / 禁用 / 清零这类) */
    function confirmAction(opts) {
        openModal({
            title: opts.title,
            icon: opts.icon || 'triangle-alert',
            danger: opts.danger !== false,
            build: function (body) {
                var box = h('div', 'modal-alert');
                box.appendChild(iconEl('triangle-alert'));
                box.appendChild(h('span', null, opts.message));
                body.appendChild(box);
            },
            buttons: [
                { text: '取消', className: 'btn btn-ghost btn-sm' },
                {
                    text: opts.confirmText || '确定',
                    className: 'btn btn-sm ' + (opts.danger === false ? 'btn-primary' : 'btn-danger'),
                    onClick: function () {
                        return opts.action().then(function () { return opts.successMessage; });
                    }
                }
            ],
            afterChange: refreshAfterUserChange
        });
    }

    // -------------------------------------------------------- 弹窗里的字段 --

    function field(labelText, control, hintText, required) {
        var wrap = h('div', 'form-field');
        var label = h('label', 'form-label', labelText);
        if (required) { label.appendChild(h('span', 'req', '*')); }
        wrap.appendChild(label);
        wrap.appendChild(control);
        if (hintText) { wrap.appendChild(h('p', 'form-hint', hintText)); }
        return wrap;
    }

    function textInput(type, placeholder, value) {
        var input = h('input', 'form-input');
        input.type = type || 'text';
        if (placeholder) { input.placeholder = placeholder; }
        if (value !== undefined && value !== null) { input.value = value; }
        return input;
    }

    function selectInput(options, value) {
        var select = h('select', 'form-select');
        options.forEach(function (pair) {
            select.appendChild(new Option(pair[1], pair[0]));
        });
        if (value !== undefined) { select.value = value; }
        return select;
    }

    /**
     * 每日上限的三态编辑控件:
     *   inherit   -> null(跟随全局)
     *   unlimited -> -1(不限)
     *   custom    -> >= 0 的具体次数
     * value() 返回 null / -1 / 数字,非法输入返回字符串 'invalid'。
     */
    function limitControl(labelText, current) {
        var mode = (current === null || current === undefined) ? 'inherit'
            : (current === -1 ? 'unlimited' : 'custom');

        var row = h('div', 'limit-row');
        row.appendChild(h('div', 'limit-name', labelText));

        var inputs = h('div', 'limit-inputs');
        var select = selectInput([['inherit', '跟随全局'], ['unlimited', '不限'], ['custom', '自定义']], mode);
        select.setAttribute('aria-label', labelText + '的每日上限方式');

        var number = textInput('number', '0', mode === 'custom' ? String(current) : '0');
        number.min = '0';
        number.step = '1';
        number.setAttribute('aria-label', labelText + '的每日上限次数');
        number.hidden = mode !== 'custom';

        var unit = h('span', 'unit', '次');
        unit.hidden = mode !== 'custom';

        select.addEventListener('change', function () {
            var custom = select.value === 'custom';
            number.hidden = !custom;
            unit.hidden = !custom;
            if (custom) { number.focus(); }
        });

        inputs.appendChild(select);
        inputs.appendChild(number);
        inputs.appendChild(unit);
        row.appendChild(inputs);

        return {
            node: row,
            value: function () {
                if (select.value === 'inherit') { return null; }
                if (select.value === 'unlimited') { return -1; }
                var text = number.value.trim();
                if (!/^\d+$/.test(text)) { return 'invalid'; }
                return Number(text);
            }
        };
    }

    // ------------------------------------------------------------ 各弹窗 --

    /** 修改角色。降级为普通用户属于危险操作,按钮会先切成"确认降级",再点一次才执行。 */
    function openRoleModal(row) {
        var isSelf = !!(state.me && state.me.id === row.id);
        var select = selectInput([['USER', '普通用户'], ['ADMIN', '管理员']],
            row.role === 'ADMIN' ? 'ADMIN' : 'USER');

        openModal({
            title: '修改角色 · ' + displayName(row),
            icon: 'user-round',
            build: function (body) {
                body.appendChild(field('角色', select,
                    '管理员可以进入管理后台。把管理员降级为普通用户属于危险操作,需要再确认一次。'));
                if (isSelf) {
                    body.appendChild(h('p', 'form-hint',
                        '这是你自己的账号:系统不允许取消自己的管理员身份,否则就没人能进管理后台了。'));
                }
                // 改了选项就把"已上膛"的状态撤掉,避免误触
                select.addEventListener('change', function () {
                    var btn = modalButtons[1];
                    if (btn && btn.dataset.armed === '1') {
                        btn.dataset.armed = '';
                        btn.className = 'btn btn-primary btn-sm';
                        btn.textContent = '保存';
                        if (modalNotice) { modalNotice.hidden = true; }
                    }
                });
            },
            buttons: [
                { text: '取消', className: 'btn btn-ghost btn-sm' },
                {
                    text: '保存',
                    className: 'btn btn-primary btn-sm',
                    onClick: function (btn) {
                        var next = select.value;
                        var danger = row.role === 'ADMIN' && next === 'USER';

                        // 第一次点击只"上膛",再点一次才真正提交
                        if (danger && btn.dataset.armed !== '1') {
                            btn.dataset.armed = '1';
                            btn.className = 'btn btn-sm btn-danger';
                            btn.textContent = '确认降级为普通用户';
                            showModalNotice('降级后【' + displayName(row)
                                + '】将无法进入管理后台。确认请再点一次按钮。', 'warn');
                            return;
                        }

                        if (next === row.role) {
                            return Promise.reject(new Error('角色没有变化'));
                        }
                        return sendJson('PUT', '/api/admin/users/' + row.id, { role: next })
                            .then(function () { return '角色已改为' + (ROLE_TEXT[next] || next); });
                    }
                }
            ],
            afterChange: refreshAfterUserChange
        });
    }

    function openPasswordModal(row) {
        var password = textInput('password', '至少 6 位');
        password.autocomplete = 'new-password';
        var again = textInput('password', '再输入一次');
        again.autocomplete = 'new-password';

        openModal({
            title: '重置密码 · ' + displayName(row),
            icon: 'key-round',
            build: function (body) {
                var grid = h('div', 'form-grid');
                grid.appendChild(field('新密码', password, null, true));
                grid.appendChild(field('确认新密码', again, null, true));
                body.appendChild(grid);
                body.appendChild(h('p', 'form-hint',
                    '重置后请把新密码告知该用户。密码长度至少 6 位。'));
            },
            buttons: [
                { text: '取消', className: 'btn btn-ghost btn-sm' },
                {
                    text: '重置密码',
                    className: 'btn btn-primary btn-sm',
                    onClick: function () {
                        var value = password.value;
                        if (value.length < 6) {
                            return Promise.reject(new Error('密码长度至少 6 位'));
                        }
                        if (value !== again.value) {
                            return Promise.reject(new Error('两次输入的密码不一致'));
                        }
                        return sendJson('PUT', '/api/admin/users/' + row.id + '/password',
                            { password: value }).then(function () { return '密码已重置'; });
                    }
                }
            ]
        });
    }

    /**
     * 每日上限。后端是整体覆盖,三个字段一起提交:
     * null = 跟随全局,-1 = 不限,>= 0 = 具体次数。
     */
    function openDailyModal(row) {
        var total = limitControl('每日总量', row.dailyLimitTotal);
        var faq = limitControl('问答', row.dailyLimitFaq);
        var sale = limitControl('售后', row.dailyLimitAfterSale);

        openModal({
            title: '每日上限 · ' + displayName(row),
            icon: 'settings',
            build: function (body) {
                var grid = h('div', 'form-grid');
                grid.appendChild(total.node);
                body.appendChild(grid);
                body.appendChild(h('p', 'form-hint',
                    '「每日总量」是这个账号一天最多能问几次,两条链路合计 —— 改完立即生效,'
                    + '用户那边的「今日总计」和「今日剩余」会跟着变。'));
                body.appendChild(h('p', 'form-hint',
                    '下面两条是可选的高级设置,用来在总量之上再单独收紧某一条链路,'
                    + '平时用不到。留「跟随全局」就行。'));
                var sub = h('div', 'form-grid');
                sub.appendChild(faq.node);
                sub.appendChild(sale.node);
                body.appendChild(sub);
                body.appendChild(h('p', 'form-hint',
                    '「跟随全局」用系统配置的每日次数;「不限」表示该链路不限制;'
                    + '「自定义」可填 0 或更大的整数(0 表示不允许使用该链路)。'
                    + '三个字段会一起保存,所以每次都要把想保留的值一起提交。'));
            },
            buttons: [
                { text: '取消', className: 'btn btn-ghost btn-sm' },
                {
                    text: '保存',
                    className: 'btn btn-primary btn-sm',
                    onClick: function () {
                        var totalValue = total.value();
                        var faqValue = faq.value();
                        var saleValue = sale.value();
                        if (totalValue === 'invalid' || faqValue === 'invalid' || saleValue === 'invalid') {
                            return Promise.reject(new Error('自定义次数请填 0 或更大的整数'));
                        }
                        return sendJson('PUT', '/api/admin/users/' + row.id + '/daily-limit',
                            { total: totalValue, faq: faqValue, afterSale: saleValue })
                            .then(function () { return '每日上限已更新'; });
                    }
                }
            ],
            afterChange: refreshAfterUserChange
        });
    }

    function openCreateModal() {
        var username = textInput('text', '登录时使用');
        var password = textInput('password', '至少 6 位');
        password.autocomplete = 'new-password';
        var nickname = textInput('text', '可选,不填就用账号');
        var role = selectInput([['USER', '普通用户'], ['ADMIN', '管理员']], 'USER');
        var total = limitControl('每日总量', null);
        var faq = limitControl('问答', null);
        var sale = limitControl('售后', null);

        openModal({
            title: '新建用户',
            icon: 'user-plus',
            wide: true,
            build: function (body) {
                var grid = h('div', 'form-grid is-two');

                grid.appendChild(field('登录账号', username, null, true));
                grid.appendChild(field('初始密码', password, null, true));
                grid.appendChild(field('昵称', nickname, '不填就用登录账号'));
                grid.appendChild(field('角色', role, '管理员可以进入管理后台'));

                var limitBox = h('div', 'form-field is-full');
                limitBox.appendChild(h('label', 'form-label', '每日上限'));
                limitBox.appendChild(total.node);
                limitBox.appendChild(h('p', 'form-hint',
                    '「每日总量」是这个账号一天最多能问几次,两条链路合计。'
                    + '默认「跟随全局」;也可以在这里直接给这个人单独设置。'));
                var subLimits = h('div', 'form-grid');
                subLimits.appendChild(faq.node);
                subLimits.appendChild(sale.node);
                limitBox.appendChild(subLimits);
                grid.appendChild(limitBox);

                body.appendChild(grid);
            },
            buttons: [
                { text: '取消', className: 'btn btn-ghost btn-sm' },
                {
                    text: '创建',
                    className: 'btn btn-primary btn-sm',
                    onClick: function () {
                        var name = username.value.trim();
                        if (!name) { return Promise.reject(new Error('请填写登录账号')); }
                        if (password.value.length < 6) {
                            return Promise.reject(new Error('密码长度至少 6 位'));
                        }
                        var totalValue = total.value();
                        var faqValue = faq.value();
                        var saleValue = sale.value();
                        if (totalValue === 'invalid' || faqValue === 'invalid' || saleValue === 'invalid') {
                            return Promise.reject(new Error('自定义次数请填 0 或更大的整数'));
                        }

                        var payload = {
                            username: name,
                            password: password.value,
                            role: role.value,
                            dailyLimitTotal: totalValue,
                            dailyLimitFaq: faqValue,
                            dailyLimitAfterSale: saleValue
                        };
                        var nick = nickname.value.trim();
                        if (nick) { payload.nickname = nick; }

                        return sendJson('POST', '/api/admin/users', payload)
                            .then(function () { return '用户已创建'; });
                    }
                }
            ],
            afterChange: function () {
                // 新建的人可能在别的页,回到第 1 页最直观
                loadUsers(1);
                loadOverview();
                loadUserOptions();
            }
        });
    }

    // ============================================================== Tab 切换

    function switchTab(name) {
        state.tab = name === 'logs' ? 'logs' : 'users';
        var isUsers = state.tab === 'users';

        el.tabButtons.forEach(function (btn) {
            var active = btn.dataset.tab === state.tab;
            btn.classList.toggle('is-active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        el.panes.users.hidden = !isUsers;
        el.panes.logs.hidden = isUsers;
        el.newUser.hidden = !isUsers;

        if (!isUsers) {
            renderLogHint();
            loadLogStats();
            loadLogs(state.logs.page);
        }
    }

    // ============================================================== 初始化

    function bindEvents() {
        el.tabButtons.forEach(function (btn) {
            btn.addEventListener('click', function () { switchTab(btn.dataset.tab); });
        });

        el.refresh.addEventListener('click', function () {
            if (state.tab === 'users') {
                loadUsers(state.users.page);
                loadOverview();
            } else {
                loadLogs(state.logs.page);
                loadLogStats();
            }
            A.toast('已刷新');
        });

        el.newUser.addEventListener('click', openCreateModal);

        el.userForm.addEventListener('submit', function (event) {
            event.preventDefault();
            loadUsers(1);
        });
        el.uRole.addEventListener('change', function () { loadUsers(1); });
        el.uReset.addEventListener('click', function () {
            el.uKeyword.value = '';
            el.uRole.value = '';
            loadUsers(1);
        });

        el.logForm.addEventListener('submit', function (event) {
            event.preventDefault();
            loadLogs(1);
            loadLogStats();
        });
        el.lType.addEventListener('change', function () { loadLogs(1); loadLogStats(); });
        el.lStatus.addEventListener('change', function () { loadLogs(1); loadLogStats(); });
        el.lUser.addEventListener('change', function () { loadLogs(1); loadLogStats(); });
        el.lReset.addEventListener('click', function () {
            el.lUser.value = '';
            el.lKeyword.value = '';
            el.lType.value = '';
            el.lStatus.value = '';
            defaultLogRange();
            renderLogHint();
            loadLogs(1);
            loadLogStats();
        });

        // 展开某一行(点击或回车)
        function rowEvent(event) {
            var tr = event.target.closest('tr[data-index]');
            if (!tr) { return; }
            if (event.type === 'keydown' && event.key !== 'Enter' && event.key !== ' ') { return; }
            event.preventDefault();
            toggleLogRow(tr);
        }
        el.logBody.addEventListener('click', rowEvent);
        el.logBody.addEventListener('keydown', rowEvent);

        el.modalClose.addEventListener('click', closeModal);
        el.modalMask.addEventListener('click', closeModal);
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && modalOpen()) { closeModal(); }
        });
    }

    function init() {
        bindEvents();
        defaultLogRange();

        // 先确认自己是不是管理员:是 -> 加载;不是 -> 提示并送回问答页
        A.me().then(function (data) {
            var user = (data && data.user) || null;
            state.me = user;
            if (!user || user.admin !== true) {
                showDenied('当前账号不是管理员,无法查看管理后台');
                return;
            }
            el.loading.hidden = true;
            el.body.hidden = false;
            loadOverview();
            loadUsers(1);
            loadUserOptions();
        }).catch(function (err) {
            // me 失败通常是登录过期(已由 layout.js 处理跳转),这里只兜底提示
            el.loading.hidden = true;
            el.deniedDesc.textContent = (err && err.message) || '无法确认当前账号的权限';
            el.denied.hidden = false;
        });
    }

    init();
})();
