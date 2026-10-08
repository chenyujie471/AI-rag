package com.example.airag.auth;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * 登录令牌的 Cookie 载体。
 *
 * <p><b>为什么除了 Authorization 头还需要 Cookie:</b> 当 FastGPT 分享页被反向代理到
 * 我们自己的源上之后,分享页的前端会以它自己的方式请求 {@code /api/...} —— 它不认识我们
 * 签发的 JWT,也没有地方让我们插一个 Authorization 头。而浏览器对<b>同源</b>请求会自动
 * 带上 Cookie,这是我们能判断"这个对话请求属于哪个账号"的唯一途径。</p>
 *
 * <p>安全约束(每一条都别随手改):</p>
 * <ul>
 *   <li>{@code HttpOnly} —— JS 读不到,降低 XSS 窃取令牌的风险;</li>
 *   <li>{@code SameSite=Lax} —— <b>这是防 CSRF 的关键</b>:跨站发起的 POST 不会带上
 *       本 Cookie;分享页的对话请求是本域发起的同源请求,不受影响;</li>
 *   <li>{@code Path=/} —— 必须覆盖 {@code /api/v1/chat/completions} 这类被拦截的路径;</li>
 *   <li>{@code Secure} —— 依据当前请求是否 HTTPS 自动决定,这样本机 HTTP 调试也能用;
 *       走 Nginx 时依赖它转发的 {@code X-Forwarded-Proto}。</li>
 * </ul>
 */
public final class AuthCookie {

    /** 与前端 localStorage 里的键名保持一致,便于排查时对照。 */
    public static final String NAME = "airag_token";

    /**
     * JWT 是 RFC 7519 的 base64url,字符集就只有这些。
     * 这里做白名单校验是为了防止把 {@code ;} 之类的字符写进 Set-Cookie 头造成头注入。
     */
    private static final String JWT_PATTERN = "[A-Za-z0-9._-]+";

    private AuthCookie() {
    }

    /** 写入登录令牌;token 非法或为空时什么也不做(不清除已有 Cookie)。 */
    public static void write(HttpServletRequest request, HttpServletResponse response,
                             String token, long maxAgeSeconds) {
        if (token == null || !token.matches(JWT_PATTERN)) {
            return;
        }
        StringBuilder header = new StringBuilder();
        header.append(NAME).append('=').append(token)
                .append("; Path=/")
                .append("; Max-Age=").append(Math.max(0L, maxAgeSeconds))
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (isSecure(request)) {
            header.append("; Secure");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, header.toString());
    }

    /** 清除登录 Cookie(登出时调用)。 */
    public static void clear(HttpServletRequest request, HttpServletResponse response) {
        StringBuilder header = new StringBuilder();
        header.append(NAME).append('=')
                .append("; Path=/")
                .append("; Max-Age=0")
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (isSecure(request)) {
            header.append("; Secure");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, header.toString());
    }

    /** 读取 Cookie 中的令牌,没有则返回 null。 */
    public static String read(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        // Nginx 反代场景:浏览器到 Nginx 可能是 HTTP,但对外是 HTTPS
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && proto.toLowerCase(Locale.ROOT).startsWith("https");
    }
}
