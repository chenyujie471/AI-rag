/**
 * 问答页逻辑(两个页面共用:faq.html / after-sale.html)。
 *
 * 每个页面在 HTML 里用一段内联配置声明自己是哪条链路:
 *
 *   window.AIRAG_PAGE = {
 *       channel: 'faq',                                  // 必填,对应后端 /api/chat/faq
 *       label:   'AI 知识问答',                          // 页面标题
 *       desc:    '基于平台知识库…',                       // 欢迎屏的说明
 *       samples: ['问题一', '问题二']                     // 猜你想问
 *   };
 *
 * 问答走的是我们自己的网关(/api/chat/{channel}),不是直连 FastGPT —— 所以
 * 额度、每日上限、内容与用量记录全部生效。后端返回 text/event-stream 时边收边渲染。
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'airag_token';
    var USER_KEY = 'airag_user';
    var CHAT_ID_PREFIX = 'airag_chatid_';
    var TRANSCRIPT_PREFIX = 'airag_transcript_';

    var LOGO_IMG = '<img class="brand-logo" src="/assets/logo.png" srcset="/assets/logo.png 1x, /assets/logo@2x.png 2x" alt="">';

    var PAGE = window.AIRAG_PAGE || {};
    var CHANNEL = PAGE.channel || 'faq';
    var PAGE_LABEL = PAGE.label || 'AI 知识问答';
    var PAGE_DESC = PAGE.desc || '基于知识库的智能问答,回答都带出处依据。';
    var SAMPLES = PAGE.samples || [];

    var el = {
        messages: document.getElementById('messages'),
        composer: document.getElementById('composer'),
        question: document.getElementById('question'),
        sendButton: document.getElementById('send-button'),
        dailyBadge: document.getElementById('daily-badge'),
        userName: document.getElementById('user-name'),
        logout: null,   // 退出按钮由 layout.js 统一绑定
        newChat: document.getElementById('new-chat-button'),
        historyToggle: document.getElementById('history-toggle'),
        historyClose: document.getElementById('history-close'),
        historyMask: document.getElementById('history-mask'),
        historyPanel: document.getElementById('history-panel'),
        historyList: document.getElementById('history-list'),
        historyRefresh: document.getElementById('history-refresh'),
        toast: document.getElementById('toast')
    };

    var state = {
        sending: false,
        quota: null,
        daily: [],
        transcript: []
    };

    // ------------------------------------------------------------- 基础工具 --

    function token() {
        return localStorage.getItem(TOKEN_KEY);
    }

    function authHeaders(extra) {
        var headers = extra || {};
        headers.Authorization = 'Bearer ' + token();
        return headers;
    }

    function clearSession() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        try {
            sessionStorage.removeItem(CHAT_ID_PREFIX + CHANNEL);
            sessionStorage.removeItem(TRANSCRIPT_PREFIX + CHANNEL);
        } catch (e) { /* 无所谓 */ }
    }

    function handleUnauthorized() {
        clearSession();
        showToast('登录已过期,正在跳回登录页…');
        window.setTimeout(function () { window.location.replace('/'); }, 1200);
    }

    function showToast(message) {
        if (!el.toast) {
            return;
        }
        el.toast.textContent = message;
        el.toast.hidden = false;
        window.clearTimeout(showToast.timer);
        showToast.timer = window.setTimeout(function () { el.toast.hidden = true; }, 3200);
    }

    function safeJson(resp) {
        return resp.json().catch(function () { return null; });
    }

    function pad(value) {
        return (value < 10 ? '0' : '') + value;
    }

    /** 兼容 ISO 字符串与 Jackson 默认的时间数组两种序列化形式。 */
    function formatTime(value) {
        if (!value) {
            return '';
        }
        if (Object.prototype.toString.call(value) === '[object Array]') {
            return value[0] + '-' + pad(value[1]) + '-' + pad(value[2]) + ' '
                + pad(value[3] || 0) + ':' + pad(value[4] || 0);
        }
        var text = String(value).replace('T', ' ');
        return text.length >= 16 ? text.slice(0, 16) : text;
    }

    function statusLabel(status) {
        if (status === 'SUCCESS') { return '成功'; }
        if (status === 'FAILED') { return '失败'; }
        if (status === 'REJECTED') { return '已拦截'; }
        return status || '';
    }

    function getChatId() {
        var key = CHAT_ID_PREFIX + CHANNEL;
        var id = sessionStorage.getItem(key);
        if (!id) {
            id = 'web' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
            sessionStorage.setItem(key, id);
        }
        return id;
    }

    function scrollToBottom() {
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    // ------------------------------------------------------- 会话内容持久化 --
    // 两个问答页是两个独立页面,切页会整页重载。把当前会话存在 sessionStorage 里,
    // 来回切换时上下文不会丢,体验接近"同一个应用的两个入口"。

    function persistTranscript() {
        try {
            sessionStorage.setItem(TRANSCRIPT_PREFIX + CHANNEL, JSON.stringify(state.transcript));
        } catch (e) { /* 超配额就算了,不影响使用 */ }
    }

    function restoreTranscript() {
        try {
            var raw = sessionStorage.getItem(TRANSCRIPT_PREFIX + CHANNEL);
            var list = raw ? JSON.parse(raw) : [];
            state.transcript = Array.isArray(list) ? list : [];
        } catch (e) {
            state.transcript = [];
        }
        state.transcript.forEach(function (item) {
            item.contentEl = null;
            // 上次没写完就关页面的,标记为已完成,避免一直显示"正在思考"
            item.pending = false;
            // 历史记录直接整段显示,不走打字机
            item.streamDone = true;
            item.typeTimer = null;
            item.shown = (item.answer || '').length;
            if (!item.answer && !item.error) {
                item.error = '这次回答没有保存下来';
            }
        });
    }

    // --------------------------------------------------------------- 渲染 --

    /** 今日次数徽标:显示真正管着次数的那个上限 */
    function renderDaily() {
        var badge = el.dailyBadge;
        if (!badge) {
            return;
        }
        var total = null;
        var channel = null;
        (state.daily || []).forEach(function (item) {
            if (item.channel === CHANNEL) {
                channel = item;
            } else if (item.channel === 'total') {
                total = item;
            }
        });
        // 两个上限都配了时显示更紧的那个 —— 否则会出现"徽标说还剩 17 次"却已经被拦住的情况。
        // 两个都没配才隐藏。
        var limited = [];
        if (total && !total.unlimited) { limited.push(total); }
        if (channel && !channel.unlimited) { limited.push(channel); }
        if (!limited.length) {
            badge.hidden = true;
            return;
        }
        var usage = limited.length === 1
            ? limited[0]
            : (limited[0].remaining <= limited[1].remaining ? limited[0] : limited[1]);
        badge.hidden = false;
        // 总量那一行要标出来,不然分不清这个数字是本链路的还是两条链路合计的
        badge.textContent = (usage === total ? '今日总计 ' : '今日 ')
            + usage.used + ' / ' + usage.limit;
        badge.classList.toggle('is-low', usage.remaining <= 0);
    }

    /**
     * 助手回答:marked 解析 Markdown,DOMPurify 过滤后再插入,防 XSS。
     * 任一库没加载成功就退回纯文本。
     */
    function renderAnswer(node, text) {
        if (window.marked && window.DOMPurify) {
            node.classList.add('md');
            node.innerHTML = window.DOMPurify.sanitize(window.marked.parse(text, { breaks: true, gfm: true }));
            Array.prototype.forEach.call(node.querySelectorAll('a[href]'), function (a) {
                a.setAttribute('target', '_blank');
                a.setAttribute('rel', 'noopener noreferrer');
            });
        } else {
            node.textContent = text;
        }
    }

    function renderUserName() {
        if (!el.userName) {
            return;
        }
        var cached = localStorage.getItem(USER_KEY);
        if (!cached) {
            return;
        }
        try {
            var user = JSON.parse(cached);
            var name = user.nickname || user.username || '已登录';
            el.userName.textContent = user.username ? name + '(' + user.username + ')' : name;
        } catch (e) {
            /* 缓存坏了就忽略 */
        }
    }

    function icon(name, className) {
        return '<svg class="icon' + (className ? ' ' + className : '') + '" aria-hidden="true"><use href="#i-'
            + name + '"></use></svg>';
    }

    function buildUserBubble(text) {
        var wrap = document.createElement('div');
        wrap.className = 'msg msg-user';

        var body = document.createElement('div');
        body.className = 'msg-body';
        var bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        body.appendChild(bubble);

        var avatar = document.createElement('div');
        avatar.className = 'msg-avatar is-user';
        avatar.textContent = '我';

        wrap.appendChild(body);
        wrap.appendChild(avatar);
        return wrap;
    }

    function buildAssistantBubble(item) {
        var wrap = document.createElement('div');
        wrap.className = 'msg msg-assistant';

        var avatar = document.createElement('div');
        avatar.className = 'msg-avatar is-ai';
        avatar.innerHTML = LOGO_IMG;

        var body = document.createElement('div');
        body.className = 'msg-body';

        var bubble = document.createElement('div');
        bubble.className = 'bubble';

        if (item.error) {
            bubble.classList.add('is-error');
            bubble.textContent = item.error;
        } else if (item.answer) {
            renderAnswer(bubble, item.answer);
        } else {
            bubble.classList.add('is-typing');
            bubble.textContent = '正在思考…';
        }

        item.contentEl = bubble;
        body.appendChild(bubble);

        // 只有拿到回答之后才给"复制"
        if (item.answer || item.error) {
            body.appendChild(buildTools(item));
        } else {
            item.toolsEl = null;
        }

        wrap.appendChild(avatar);
        wrap.appendChild(body);
        return wrap;
    }

    function buildTools(item) {
        var tools = document.createElement('div');
        tools.className = 'msg-tools';

        var copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'tool-btn';
        copy.title = '复制回答';
        copy.innerHTML = icon('copy') + '<span>复制</span>';
        copy.addEventListener('click', function () {
            copyText(item.answer || item.error || '', copy);
        });

        tools.appendChild(copy);
        item.toolsEl = tools;
        return tools;
    }

    function copyText(text, button) {
        function done() {
            var label = button.querySelector('span');
            if (!label) {
                return;
            }
            var original = label.textContent;
            label.textContent = '已复制';
            window.setTimeout(function () { label.textContent = original; }, 1500);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done).catch(function () { showToast('复制失败,请手动选择'); });
            return;
        }
        // 非 HTTPS 环境下 navigator.clipboard 通常不可用,退回 textarea 方案
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', 'readonly');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        try {
            document.execCommand('copy');
            done();
        } catch (e) {
            showToast('复制失败,请手动选择');
        }
        document.body.removeChild(area);
    }

    /** 欢迎屏:大图标 + 标题 + 说明 + 猜你想问 */
    function buildWelcome() {
        var wrap = document.createElement('div');
        wrap.className = 'welcome';

        var mark = document.createElement('div');
        mark.className = 'welcome-mark';
        mark.innerHTML = LOGO_IMG;

        var title = document.createElement('h1');
        title.className = 'welcome-title';
        title.textContent = PAGE_LABEL;

        var desc = document.createElement('p');
        desc.className = 'welcome-desc';
        desc.textContent = PAGE_DESC;

        wrap.appendChild(mark);
        wrap.appendChild(title);
        wrap.appendChild(desc);

        if (SAMPLES.length) {
            var label = document.createElement('div');
            label.className = 'welcome-label';
            label.textContent = '猜你想问';

            var chips = document.createElement('div');
            chips.className = 'welcome-chips';
            SAMPLES.forEach(function (question) {
                var chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'chip';
                chip.textContent = question;
                chip.addEventListener('click', function () {
                    if (state.sending) {
                        return;
                    }
                    send(question);
                });
                chips.appendChild(chip);
            });

            wrap.appendChild(label);
            wrap.appendChild(chips);
        }

        return wrap;
    }

    function renderTranscript() {
        el.messages.innerHTML = '';
        if (!state.transcript.length) {
            el.messages.appendChild(buildWelcome());
            return;
        }
        state.transcript.forEach(function (item) {
            el.messages.appendChild(buildUserBubble(item.question));
            if (item.answer || item.error || item.pending) {
                el.messages.appendChild(buildAssistantBubble(item));
            }
        });
        scrollToBottom();
    }

    // --------------------------------------------------------- 打字机渲染 --

    // 上游有两种情况:FastGPT 的「AI 对话」节点是真流式(一小段一小段地推),
    // 而「指定回复」「代码运行」这类节点会在结束时把整段文字一次性吐下来。
    // 后者如果收到就立刻渲染,回答会"啪"地一下全出现,看起来像卡了很久然后突然出结果。
    //
    // 所以这里把「收到多少」和「画出多少」分开:
    //   item.answer = 已从上游收到的完整文本(持久化用的是它,不是画出来的部分)
    //   item.shown  = 已画到屏幕上的字符数
    // 由定时器匀速把 shown 往 answer 上追。两种情况都能得到平滑的输出。
    var TYPE_TICK_MS = 40;        // 25fps,肉眼足够顺;再快只是让它更像"闪现"
    var TYPE_MIN_CHARS = 2;       // 每帧至少吐这么多,避免收尾拖成"一字一顿"
    var TYPE_CATCHUP_TICKS = 24;  // 积压很多时按"约 1 秒内追平"加速,不会越落越远
    var REDUCED_MOTION = !!(window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    function typewriterStart(item) {
        if (item.typeTimer) {
            return;
        }
        item.typeTimer = setInterval(function () { typewriterTick(item); }, TYPE_TICK_MS);
    }

    function typewriterStop(item) {
        if (item.typeTimer) {
            clearInterval(item.typeTimer);
            item.typeTimer = null;
        }
    }

    function typewriterTick(item) {
        var backlog = item.answer.length - item.shown;
        if (backlog > 0) {
            var step = REDUCED_MOTION ? backlog
                : Math.max(TYPE_MIN_CHARS, Math.ceil(backlog / TYPE_CATCHUP_TICKS));
            item.shown = Math.min(item.answer.length, item.shown + step);
            paintAnswer(item);
            return;
        }
        // 内容画完了,而且上游也推完了 —— 这才收尾
        if (item.streamDone) {
            typewriterStop(item);
            finalizeItem(item);
        }
    }

    /** 把 item.answer 的前 item.shown 个字符画到屏幕上。 */
    function paintAnswer(item) {
        if (!item.contentEl) {
            return;
        }
        if (item.pending) {
            item.pending = false;
            item.contentEl.classList.remove('is-typing');
            item.contentEl.classList.add('is-streaming');
        }
        renderAnswer(item.contentEl, item.answer.slice(0, item.shown));
        scrollToBottom();
    }

    function appendChunk(item, chunk) {
        // 只累积,不直接渲染 —— 交给打字机匀速画出来
        item.answer += chunk;
        typewriterStart(item);
    }

    /** 上游推完了。剩下的交给打字机画完,画完才收尾。 */
    function finishItem(item) {
        item.streamDone = true;
        if (item.answer.length - item.shown <= 0) {
            typewriterStop(item);
            finalizeItem(item);
        } else {
            typewriterStart(item);
        }
    }

    /** 真正的收尾:去掉光标、补上"复制"、落一次持久化。 */
    function finalizeItem(item) {
        typewriterStop(item);
        item.pending = false;
        item.shown = item.answer.length;
        if (item.contentEl) {
            item.contentEl.classList.remove('is-streaming', 'is-typing');
        }
        // 流式时没来得及加"复制",这里补上
        if (!item.toolsEl && item.contentEl && item.contentEl.parentNode) {
            item.contentEl.parentNode.appendChild(buildTools(item));
        }
        persistTranscript();
    }

    function setError(item, message) {
        typewriterStop(item);
        item.streamDone = true;
        item.pending = false;
        item.error = message;
        if (item.contentEl) {
            item.contentEl.classList.remove('is-typing', 'is-streaming');
            item.contentEl.classList.add('is-error');
            item.contentEl.textContent = message;
        }
        if (!item.toolsEl && item.contentEl && item.contentEl.parentNode) {
            item.contentEl.parentNode.appendChild(buildTools(item));
        }
        persistTranscript();
    }

    // ------------------------------------------------------------- SSE 解析 --

    function handleSseLine(line, onChunk) {
        var trimmed = line.trim();
        if (!trimmed || trimmed.charAt(0) === ':') {
            return;                      // 空行 / keep-alive 注释
        }
        if (trimmed.indexOf('data:') !== 0) {
            return;                      // event: 之类的行直接跳过
        }
        var payload = trimmed.slice(5).trim();
        if (!payload || payload === '[DONE]') {
            return;
        }
        var json;
        try {
            json = JSON.parse(payload);
        } catch (e) {
            return;
        }
        var choices = json && json.choices;
        if (choices && choices.length) {
            var delta = choices[0].delta;
            if (delta && typeof delta.content === 'string' && delta.content.length) {
                onChunk(delta.content);
            }
        }
    }

    function readSse(resp, onChunk) {
        var reader = resp.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var buffer = '';

        function drain() {
            var index = buffer.indexOf('\n');
            while (index >= 0) {
                var line = buffer.slice(0, index);
                buffer = buffer.slice(index + 1);
                if (line.charAt(line.length - 1) === '\r') {
                    line = line.slice(0, -1);
                }
                handleSseLine(line, onChunk);
                index = buffer.indexOf('\n');
            }
        }

        function pump() {
            return reader.read().then(function (result) {
                if (result.done) {
                    drain();
                    if (buffer) {
                        handleSseLine(buffer, onChunk);
                        buffer = '';
                    }
                    return null;
                }
                buffer += decoder.decode(result.value, { stream: true });
                drain();
                return pump();
            });
        }

        return pump();
    }

    // --------------------------------------------------------------- 发送 --

    function send(questionText) {
        var item = {
            question: questionText, answer: '', error: null, pending: true,
            shown: 0, streamDone: false, typeTimer: null,
            contentEl: null, toolsEl: null
        };
        state.transcript.push(item);

        var welcome = el.messages.querySelector('.welcome');
        if (welcome) {
            welcome.remove();
        }
        el.messages.appendChild(buildUserBubble(questionText));
        el.messages.appendChild(buildAssistantBubble(item));
        scrollToBottom();
        persistTranscript();

        state.sending = true;
        el.sendButton.disabled = true;

        return fetch('/api/chat/' + CHANNEL, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ question: questionText, chatId: getChatId(), stream: true })
        })
            .then(function (resp) {
                if (resp.status === 401) {
                    setError(item, '登录已过期');
                    handleUnauthorized();
                    return null;
                }
                var contentType = (resp.headers.get('content-type') || '').toLowerCase();
                if (!resp.ok) {
                    return safeJson(resp).then(function (body) {
                        setError(item, (body && body.message) || ('请求失败(HTTP ' + resp.status + ')'));
                        return null;
                    });
                }
                if (contentType.indexOf('text/event-stream') >= 0) {
                    return readSse(resp, function (chunk) { appendChunk(item, chunk); }).then(function () {
                        if (!item.answer) {
                            setError(item, '服务端没有返回任何内容');
                        } else {
                            finishItem(item);
                        }
                    });
                }
                return safeJson(resp).then(function (body) {
                    if (!body || body.code !== 0) {
                        setError(item, (body && body.message) || '返回内容异常');
                        return;
                    }
                    item.answer = (body.data && body.data.answer) || '';
                    if (!item.answer) {
                        setError(item, '服务端没有返回任何内容');
                        return;
                    }
                    if (item.contentEl) {
                        item.contentEl.classList.remove('is-typing', 'is-streaming');
                    }
                    // 只刷新每日次数徽标:终身额度已退役,响应里那个 quota 字段不再用于展示
                    if (body.data && body.data.daily) {
                        state.daily = body.data.daily;
                        renderDaily();
                    }
                    // 不整段直接渲染 —— 交给打字机匀速画,和流式路径保持同样的观感
                    finishItem(item);
                });
            })
            .catch(function (error) {
                setError(item, '网络异常:' + (error && error.message ? error.message : '未知错误'));
            })
            .finally(function () {
                state.sending = false;
                el.sendButton.disabled = false;
                autoGrow();
                el.question.focus();
                refreshQuota();
                if (el.historyPanel && !el.historyPanel.hidden) {
                    loadHistory();
                }
            });
    }

    // --------------------------------------------------------- 额度 / 历史 --

    function refreshQuota() {
        return fetch('/api/auth/me', { headers: authHeaders() })
            .then(function (resp) {
                if (resp.status === 401) {
                    handleUnauthorized();
                    return null;
                }
                return safeJson(resp);
            })
            .then(function (body) {
                if (body && body.code === 0) {
                    state.daily = body.data.daily || [];
                    renderDaily();
                    if (body.data.user) {
                        localStorage.setItem(USER_KEY, JSON.stringify(body.data.user));
                        renderUserName();
                    }
                }
            })
            .catch(function () { /* 静默失败,下次再刷 */ });
    }

    function loadHistory() {
        el.historyList.innerHTML = '<p class="drawer-empty">加载中…</p>';
        return fetch('/api/records/' + CHANNEL + '?page=1&size=30', { headers: authHeaders() })
            .then(function (resp) { return safeJson(resp); })
            .then(function (body) {
                if (!body || body.code !== 0) {
                    el.historyList.innerHTML = '<p class="drawer-empty">加载失败</p>';
                    return;
                }
                var items = (body.data && body.data.items) || [];
                if (!items.length) {
                    el.historyList.innerHTML = '<p class="drawer-empty">暂无记录</p>';
                    return;
                }
                el.historyList.innerHTML = '';
                items.forEach(function (row) {
                    var card = document.createElement('button');
                    card.type = 'button';
                    card.className = 'drawer-item';

                    var question = document.createElement('div');
                    question.className = 'drawer-q';
                    question.textContent = row.question;

                    var meta = document.createElement('div');
                    meta.className = 'drawer-meta';
                    meta.textContent = formatTime(row.createdAt) + ' · ' + statusLabel(row.status)
                        + ' · 扣 ' + row.costQuota + ' 次';
                    card.classList.add('is-' + String(row.status || '').toLowerCase());

                    card.appendChild(question);
                    card.appendChild(meta);
                    card.addEventListener('click', function () {
                        el.question.value = row.question;
                        autoGrow();
                        closeHistory();
                        el.question.focus();
                    });
                    el.historyList.appendChild(card);
                });
            })
            .catch(function () {
                el.historyList.innerHTML = '<p class="drawer-empty">加载失败</p>';
            });
    }

    // --------------------------------------------------------------- 交互 --

    function openHistory() {
        el.historyPanel.hidden = false;
        el.historyMask.hidden = false;
        loadHistory();
    }

    function closeHistory() {
        el.historyPanel.hidden = true;
        el.historyMask.hidden = true;
    }

    function newConversation() {
        if (state.sending) {
            showToast('正在等待回答,请稍候');
            return;
        }
        state.transcript.forEach(typewriterStop);
        sessionStorage.removeItem(CHAT_ID_PREFIX + CHANNEL);
        state.transcript = [];
        persistTranscript();
        renderTranscript();
        showToast('已开启新对话');
    }

    function doLogout() {
        fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() })
            .catch(function () { /* 无状态令牌,失败也无所谓 */ })
            .finally(function () {
                clearSession();
                window.location.replace('/');
            });
    }

    /** 输入框随内容长高,最多 6 行 */
    function autoGrow() {
        el.question.style.height = 'auto';
        el.question.style.height = Math.min(el.question.scrollHeight, 160) + 'px';
    }

    function submitCurrentInput() {
        if (state.sending) {
            return;
        }
        var text = el.question.value.trim();
        if (!text) {
            return;
        }
        el.question.value = '';
        autoGrow();
        send(text);
    }

    function init() {
        if (!token()) {
            window.location.replace('/');
            return;
        }
        renderUserName();
        restoreTranscript();
        renderTranscript();
        refreshQuota();
        autoGrow();

        el.composer.addEventListener('submit', function (event) {
            event.preventDefault();
            submitCurrentInput();
        });

        el.question.addEventListener('input', autoGrow);

        el.question.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                submitCurrentInput();
            }
        });

        el.newChat.addEventListener('click', newConversation);
        el.historyToggle.addEventListener('click', openHistory);
        el.historyRefresh.addEventListener('click', loadHistory);
        if (el.logout) {
            el.logout.addEventListener('click', doLogout);
        }
        if (el.historyClose) {
            el.historyClose.addEventListener('click', closeHistory);
        }
        if (el.historyMask) {
            el.historyMask.addEventListener('click', closeHistory);
        }
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && !el.historyPanel.hidden) {
                closeHistory();
            }
        });
    }

    init();
})();
