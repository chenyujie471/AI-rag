package com.example.airag.ratelimit;

/**
 * 轻量路径匹配:支持三种写法,避免依赖 Spring 内部 API 的版本差异。
 * <ul>
 *   <li>精确匹配:{@code /api/auth/login}</li>
 *   <li>前缀匹配:{@code /api/chat/**}</li>
 *   <li>单层匹配:{@code /api/*}</li>
 * </ul>
 */
final class PathPatternMatcher {

    private static final String DEEP_SUFFIX = "/**";
    private static final String SINGLE_SUFFIX = "/*";

    private PathPatternMatcher() {
    }

    static boolean matches(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith(DEEP_SUFFIX)) {
            String prefix = pattern.substring(0, pattern.length() - DEEP_SUFFIX.length());
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (pattern.endsWith(SINGLE_SUFFIX)) {
            String prefix = pattern.substring(0, pattern.length() - SINGLE_SUFFIX.length());
            if (!path.startsWith(prefix + "/")) {
                return false;
            }
            return path.indexOf('/', prefix.length() + 1) < 0;
        }
        return false;
    }
}
