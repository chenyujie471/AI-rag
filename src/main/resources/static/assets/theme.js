/**
 * 主题切换(登录页与对话页共用)。
 *
 * 真正的"首帧无闪烁"逻辑是放在各页面 <head> 里的一小段内联脚本,
 * 这里只负责按钮交互。
 */
(function () {
    'use strict';

    var KEY = 'airag_theme';
    var DARK_QUERY = '(prefers-color-scheme: dark)';

    function systemTheme() {
        return window.matchMedia && window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
    }

    function storedTheme() {
        try {
            return localStorage.getItem(KEY);
        } catch (e) {
            return null;
        }
    }

    function current() {
        return document.documentElement.getAttribute('data-theme') || 'light';
    }

    function apply(theme, persist) {
        document.documentElement.setAttribute('data-theme', theme);
        if (persist) {
            try {
                localStorage.setItem(KEY, theme);
            } catch (e) {
                /* 隐私模式下写不了,忽略 */
            }
        }
        var button = document.getElementById('theme-toggle');
        if (button) {
            var next = theme === 'dark' ? '浅色' : '深色';
            button.setAttribute('aria-label', '切换到' + next + '模式');
            button.setAttribute('title', '切换到' + next + '模式');
        }
    }

    function init() {
        var button = document.getElementById('theme-toggle');
        // 用户没手动选过时,跟随系统主题变化
        if (window.matchMedia) {
            var query = window.matchMedia(DARK_QUERY);
            var onChange = function (event) {
                if (!storedTheme()) {
                    apply(event.matches ? 'dark' : 'light', false);
                }
            };
            if (query.addEventListener) {
                query.addEventListener('change', onChange);
            } else if (query.addListener) {
                query.addListener(onChange);
            }
        }
        if (!button) {
            return;
        }
        apply(current(), false);
        button.addEventListener('click', function () {
            apply(current() === 'dark' ? 'light' : 'dark', true);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // 供 <head> 内联脚本复用
    window.AiRagTheme = { systemTheme: systemTheme, storedTheme: storedTheme };
})();
