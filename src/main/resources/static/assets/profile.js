/**
 * 个人信息页:账号 ID / 状态 / 今日剩余 / 今日各链路用量 / 累计调用统计。
 * 终身额度(sys_user.quota_total)已退役不再参与拦截,页面也不再展示、不再读 me.quota。
 */
(function () {
    'use strict';

    var A = window.AIRAG;
    if (!A) { return; }

    var $ = function (id) { return document.getElementById(id); };

    function text(id, value) {
        var node = $(id);
        if (node) { node.textContent = value == null || value === '' ? '—' : value; }
    }

    function renderUser(user) {
        var name = user.nickname || user.username || '—';
        text('p-avatar', name.slice(0, 1).toUpperCase());
        text('p-nickname', name);
        text('p-username', user.username);
        text('p-id', user.id);
        var enabled = user.status === 1 || user.status == null;
        var status = $('p-status');
        status.textContent = enabled ? '正常' : '已禁用';
        status.className = 'tag ' + (enabled ? 'tag-green' : 'tag-red');

        text('i-id', user.id);
        text('i-username', user.username);
        text('i-nickname', user.nickname);
        text('i-status', enabled ? '正常' : '已禁用');
        text('i-created', A.formatTime(user.createdAt, true));
        text('i-login', A.formatTime(user.lastLoginAt, true));
    }

    /** 从 /api/auth/me 的 daily 数组里取「今日总计」那一行(两条链路合计,后端保证它排在最后) */
    function totalDaily(list) {
        var items = list || [];
        for (var i = 0; i < items.length; i++) {
            if (items[i] && items[i].channel === 'total') { return items[i]; }
        }
        return null;
    }

    /**
     * 今日用量圆环。数据源是 daily 里的 total 行(两条链路合计)。
     *
     * <p>「每日总量」默认是<b>不限</b>的 —— 它只是两条链路相加的统计数字,不是限制;
     * 真正限流的是每条链路各自的每日上限。所以环心报"今天一共问了多少次"这个事实,
     * 而不是一个没有分母的"剩余"。只有管理员给这个账号单独设了每日总量时,
     * 环才有"用掉多少 / 上限多少"的意义,那时才会画进度并转红提醒。</p>
     */
    function renderToday(list) {
        var ring = $('q-ring');
        var today = totalDaily(list);
        if (!today) {
            // 拿不到今日数据时留空占位,圆环归零,免得把上一次的进度留在界面上误导人
            text('q-used', '—');
            text('q-limit', '—');
            text('q-left', '—');
            ring.style.setProperty('--p', 0);
            ring.classList.remove('is-low');
            return;
        }
        text('q-used', today.used);
        if (today.unlimited) {
            text('q-limit', '不限');
            text('q-left', '不限');
            // 没有分母可算,环画满,并且不要打"次数将尽"的红
            ring.style.setProperty('--p', 100);
            ring.classList.remove('is-low');
            return;
        }
        text('q-limit', today.limit + ' 次');
        text('q-left', today.remaining + ' 次');
        // 环心报的是"已用",所以环也按已用占上限的比例来画(越用越满);
        // 但"次数将尽"的红色按剩余算 —— 剩不到一成时,环接近满格且转红,是同一个意思
        var usedPct = today.limit > 0 ? Math.round(today.used * 100 / today.limit) : 100;
        var leftPct = today.limit > 0 ? Math.round(today.remaining * 100 / today.limit) : 0;
        ring.style.setProperty('--p', usedPct);
        ring.classList.toggle('is-low', leftPct <= 10);
    }

    function renderDaily(list) {
        var box = $('daily-list');
        var html = '<div class="daily-title">' + A.icon('calendar-clock') + '今日用量</div>';
        (list || []).forEach(function (d) {
            var c = A.channels[d.channel] || { label: d.channel, tone: 'gray' };
            var pct = d.unlimited || d.limit <= 0 ? 0 : Math.min(100, Math.round(d.used * 100 / d.limit));
            html += '<div class="daily-row">'
                + '<div class="daily-head"><span class="tag tag-' + c.tone + '">' + c.label + '</span>'
                + '<span class="daily-num">' + d.used + ' / ' + (d.unlimited ? '不限' : d.limit) + '</span></div>'
                + '<div class="bar is-' + c.tone + '"><span style="width:' + (d.unlimited ? 0 : pct) + '%"></span></div>'
                + '<div class="daily-foot">' + (d.unlimited ? '每日次数不限' : '今日剩余 ' + d.remaining + ' 次') + '</div>'
                + '</div>';
        });
        box.innerHTML = html;
    }

    function renderStats(stats) {
        var rows = [
            { key: 'faq', data: stats['faq'] || {} },
            { key: 'after-sale', data: stats['after-sale'] || {} }
        ];
        var html = '<table class="stat-table"><thead><tr><th>类型</th><th>总调用</th><th>成功</th>'
            + '<th>失败</th><th>拦截</th></tr></thead><tbody>';
        var sum = { total: 0, success: 0, failed: 0, rejected: 0 };
        rows.forEach(function (r) {
            var c = A.channels[r.key];
            var d = r.data;
            Object.keys(sum).forEach(function (k) { sum[k] += d[k] || 0; });
            html += '<tr><td><span class="tag tag-' + c.tone + '">' + c.label + '</span></td>'
                + '<td>' + (d.total || 0) + '</td><td class="t-green">' + (d.success || 0) + '</td>'
                + '<td class="t-red">' + (d.failed || 0) + '</td><td class="t-amber">' + (d.rejected || 0) + '</td>'
                + '</tr>';
        });
        html += '</tbody><tfoot><tr><td>合计</td><td>' + sum.total + '</td><td>' + sum.success + '</td><td>'
            + sum.failed + '</td><td>' + sum.rejected + '</td></tr></tfoot></table>';
        $('stats-body').innerHTML = html;
    }

    function load(refresh) {
        A.me(refresh).then(function (data) {
            if (!data) { return; }
            renderUser(data.user || {});
            renderToday(data.daily);
            renderDaily(data.daily);
        }).catch(function (err) { A.toast(err.message); });

        A.api('/api/logs/stats').then(renderStats).catch(function (err) {
            $('stats-body').innerHTML = '<p class="t-empty is-error">' + A.escapeHtml(err.message) + '</p>';
        });
    }

    $('p-refresh').addEventListener('click', function () {
        load(true);
        A.toast('已刷新');
    });
    load(false);
})();
