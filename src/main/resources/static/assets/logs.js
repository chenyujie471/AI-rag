/**
 * 使用日志页:两条链路合并的调用记录,支持时间 / 关键字 / 类型 / 状态筛选,
 * 点击行展开右侧详情(完整问题 + Markdown 渲染的回答)。
 * 公共能力(api / 时间格式化 / 标签配色)来自 layout.js 暴露的 window.AIRAG。
 */
(function () {
    'use strict';

    var A = window.AIRAG;
    if (!A) { return; }

    var PAGE_SIZE = 20;
    var $ = function (id) { return document.getElementById(id); };
    var el = {
        form: $('filter-form'),
        start: $('f-start'),
        end: $('f-end'),
        keyword: $('f-keyword'),
        type: $('f-type'),
        status: $('f-status'),
        reset: $('f-reset'),
        summary: $('summary'),
        body: $('log-body'),
        pager: $('pager'),
        detail: $('detail'),
        detailBody: $('detail-body'),
        detailClose: $('detail-close'),
        detailMask: $('detail-mask')
    };

    var state = { page: 1, total: 0, items: [], loading: false };

    function tag(text, tone) {
        return '<span class="tag tag-' + tone + '">' + A.escapeHtml(text) + '</span>';
    }

    function channelTag(channel) {
        var c = A.channels[channel] || { label: channel, tone: 'gray' };
        return tag(c.label, c.tone);
    }

    function statusTag(status) {
        var s = A.statuses[status] || { label: status || '—', tone: 'gray' };
        return tag(s.label, s.tone);
    }

    function durationTag(ms) {
        var tone = ms >= 20000 ? 'red' : ms >= 8000 ? 'amber' : 'green';
        return tag(A.formatDuration(ms), tone);
    }

    function plain(text, max) {
        var t = String(text || '').replace(/\s+/g, ' ').trim();
        return t.length > max ? t.slice(0, max) + '…' : t;
    }

    // ------------------------------------------------------------ 查询 --

    function currentQuery() {
        var params = new URLSearchParams();
        if (el.start.value) { params.set('start', el.start.value); }
        if (el.end.value) { params.set('end', el.end.value); }
        if (el.keyword.value.trim()) { params.set('keyword', el.keyword.value.trim()); }
        if (el.type.value) { params.set('type', el.type.value); }
        if (el.status.value) { params.set('status', el.status.value); }
        return params;
    }

    function load(page) {
        if (state.loading) { return; }
        state.loading = true;
        state.page = page || 1;
        var params = currentQuery();
        params.set('page', state.page);
        params.set('size', PAGE_SIZE);
        el.body.innerHTML = '<tr><td colspan="7" class="t-empty">加载中…</td></tr>';

        A.api('/api/logs?' + params.toString())
            .then(function (data) {
                state.total = data.total;
                state.items = data.items || [];
                renderRows();
                renderPager();
            })
            .catch(function (err) {
                el.body.innerHTML = '<tr><td colspan="7" class="t-empty is-error">' + A.escapeHtml(err.message) + '</td></tr>';
                el.pager.innerHTML = '';
            })
            .finally(function () { state.loading = false; });
    }

    function renderRows() {
        if (!state.items.length) {
            el.body.innerHTML = '<tr><td colspan="7" class="t-empty">'
                + A.icon('inbox') + '<div>没有符合条件的记录</div></td></tr>';
            return;
        }
        var html = '';
        state.items.forEach(function (row, index) {
            var answer = row.status === 'SUCCESS' ? plain(row.answer, 80) : (row.errorMsg || plain(row.answer, 80));
            html += '<tr data-index="' + index + '" tabindex="0">'
                + '<td class="c-time"><span class="caret">' + A.icon('chevron-right') + '</span>'
                + A.escapeHtml(A.formatTime(row.createdAt, true)) + '</td>'
                + '<td class="c-type">' + channelTag(row.channel) + '</td>'
                + '<td class="c-q"><div class="clip" title="' + A.escapeHtml(row.question) + '">'
                + A.escapeHtml(plain(row.question, 60)) + '</div></td>'
                + '<td class="c-a"><div class="clip' + (row.status === 'SUCCESS' ? '' : ' is-muted') + '">'
                + A.escapeHtml(answer || '—') + '</div></td>'
                + '<td class="c-status">' + statusTag(row.status) + '</td>'
                + '<td class="c-dur">' + durationTag(row.durationMs) + '</td>'
                + '<td class="c-cost">' + (row.costQuota || 0) + '</td>'
                + '</tr>';
        });
        el.body.innerHTML = html;
    }

    function renderPager() {
        var pages = Math.max(1, Math.ceil(state.total / PAGE_SIZE));
        var p = state.page;
        var html = '<span class="pager-info">共 ' + state.total + ' 条</span>';
        html += '<button type="button" class="pg" data-page="' + (p - 1) + '"' + (p <= 1 ? ' disabled' : '') + '>'
            + A.icon('chevron-left') + '</button>';
        var from = Math.max(1, p - 2);
        var to = Math.min(pages, from + 4);
        from = Math.max(1, to - 4);
        for (var i = from; i <= to; i++) {
            html += '<button type="button" class="pg' + (i === p ? ' is-active' : '') + '" data-page="' + i + '">' + i + '</button>';
        }
        html += '<button type="button" class="pg" data-page="' + (p + 1) + '"' + (p >= pages ? ' disabled' : '') + '>'
            + A.icon('chevron-right') + '</button>';
        el.pager.innerHTML = html;
    }

    /** 从 /api/auth/me 的 daily 数组里取某条链路的今日用量 */
    function dailyOf(me, channel) {
        var list = (me && me.daily) || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].channel === channel) { return list[i]; }
        }
        return null;
    }

    function loadSummary() {
        Promise.all([A.api('/api/logs/stats'), A.me()])
            .then(function (res) {
                var stats = res[0] || {};
                var me = res[1] || {};
                var faq = stats['faq'] || {};
                var as = stats['after-sale'] || {};
                var todayFaq = dailyOf(me, 'faq');
                var todaySale = dailyOf(me, 'after-sale');
                // 终身额度(me.quota)已退役;「每日总量」默认也是不限的(两条链路合计只是统计),
                // 所以这一栏不再报"剩余"—— 那永远是"不限"。真正限流的是每条链路自己的每日上限,
                // 想知道每条还差多少,去 /profile.html 看「今日用量」的明细。
                var todayUsed = (todayFaq ? todayFaq.used : 0) + (todaySale ? todaySale.used : 0);
                el.summary.innerHTML =
                    item('今日调用', todayUsed, '次', 'blue')
                    + item('今日问答 / 售后',
                        (todayFaq ? todayFaq.used : 0) + ' / ' + (todaySale ? todaySale.used : 0), '次')
                    + item('累计调用', (faq.total || 0) + (as.total || 0), '次')
                    + item('AI 知识问答', faq.total || 0, '次', 'blue')
                    + item('售后知识问答', as.total || 0, '次', 'teal')
                    + item('成功', (faq.success || 0) + (as.success || 0), '次', 'green')
                    + item('失败 / 拦截', (faq.failed || 0) + (as.failed || 0) + (faq.rejected || 0) + (as.rejected || 0), '次', 'red');
            })
            .catch(function () { el.summary.innerHTML = ''; });

        function item(label, value, unit, tone) {
            return '<div class="sum-item' + (tone ? ' is-' + tone : '') + '"><span>' + label + '</span>'
                + '<strong>' + value + '<small>' + unit + '</small></strong></div>';
        }
    }

    // ------------------------------------------------------------ 详情 --

    function openDetail(row) {
        var html = ''
            + '<dl class="kv">'
            + '<dt>时间</dt><dd>' + A.escapeHtml(A.formatTime(row.createdAt, true)) + '</dd>'
            + '<dt>类型</dt><dd>' + channelTag(row.channel) + '</dd>'
            + '<dt>状态</dt><dd>' + statusTag(row.status) + '</dd>'
            + '<dt>耗时</dt><dd>' + A.escapeHtml(A.formatDuration(row.durationMs)) + '</dd>'
            + '<dt>扣减次数</dt><dd>' + (row.costQuota || 0) + '</dd>'
            + '<dt>会话 ID</dt><dd class="mono">' + A.escapeHtml(row.sessionId || '—') + '</dd>'
            + '<dt>记录 ID</dt><dd class="mono">' + A.escapeHtml(row.channel + '#' + row.id) + '</dd>'
            + '</dl>'
            + (row.errorMsg ? '<div class="d-block"><div class="d-label">失败原因</div><div class="d-error">'
                + A.escapeHtml(row.errorMsg) + '</div></div>' : '')
            + '<div class="d-block"><div class="d-label">问题</div><div class="d-question">'
            + A.escapeHtml(row.question) + '</div></div>'
            + '<div class="d-block"><div class="d-label">回答</div><div class="d-answer" id="d-answer"></div></div>';
        el.detailBody.innerHTML = html;
        var answerNode = $('d-answer');
        if (row.answer) {
            A.renderMarkdown(answerNode, row.answer);
        } else {
            answerNode.classList.add('is-muted');
            answerNode.textContent = '(无回答)';
        }
        el.detail.hidden = false;
        el.detailMask.hidden = false;
    }

    function closeDetail() {
        el.detail.hidden = true;
        el.detailMask.hidden = true;
    }

    // ------------------------------------------------------------ 初始化 --

    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function localInput(d) {
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    function defaultRange() {
        var now = new Date();
        var from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6, 0, 0, 0);
        var to = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59);
        el.start.value = localInput(from);
        el.end.value = localInput(to);
    }

    function init() {
        defaultRange();
        var preset = new URLSearchParams(window.location.search).get('type');
        if (preset === 'faq' || preset === 'after-sale') { el.type.value = preset; }

        el.form.addEventListener('submit', function (e) {
            e.preventDefault();
            load(1);
        });
        el.type.addEventListener('change', function () { load(1); });
        el.status.addEventListener('change', function () { load(1); });
        el.reset.addEventListener('click', function () {
            el.keyword.value = '';
            el.type.value = '';
            el.status.value = '';
            defaultRange();
            load(1);
        });
        el.pager.addEventListener('click', function (e) {
            var btn = e.target.closest('.pg');
            if (btn && !btn.disabled) { load(Number(btn.getAttribute('data-page'))); }
        });
        function rowAction(e) {
            var tr = e.target.closest('tr[data-index]');
            if (!tr) { return; }
            if (e.type === 'keydown' && e.key !== 'Enter') { return; }
            openDetail(state.items[Number(tr.getAttribute('data-index'))]);
        }
        el.body.addEventListener('click', rowAction);
        el.body.addEventListener('keydown', rowAction);
        el.detailClose.addEventListener('click', closeDetail);
        el.detailMask.addEventListener('click', closeDetail);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !el.detail.hidden) { closeDetail(); }
        });

        loadSummary();
        load(1);
    }

    init();
})();
