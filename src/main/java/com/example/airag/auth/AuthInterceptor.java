package com.example.airag.auth;

import java.io.IOException;

import com.example.airag.common.ApiResponse;
import com.example.airag.common.BizException;
import com.example.airag.common.JsonResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器:按 {@code Authorization: Bearer <token>} → {@code ?token=} →
 * {@link AuthCookie} 的顺序解析令牌,写入 {@link UserContext}。
 *
 * <p>Cookie 是最后兜底的一环,专门服务于"FastGPT 分享页被反代到我们源上、
 * 它自己发的同源请求带不了自定义头"这个场景。</p>
 *
 * <p>失败时直接写 401 JSON 并中断请求,不依赖 @ExceptionHandler。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final JsonResponseWriter responseWriter;

    public AuthInterceptor(JwtService jwtService, JsonResponseWriter responseWriter) {
        this.jwtService = jwtService;
        this.responseWriter = responseWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检请求不带令牌,直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            JwtService.JwtPayload payload = jwtService.verify(resolveToken(request));
            UserContext.set(new LoginUser(payload.userId(), payload.username()));
            return true;
        } catch (BizException ex) {
            UserContext.clear();
            log.debug("鉴权失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            responseWriter.write(response, HttpStatus.UNAUTHORIZED, ApiResponse.fail(ex.getCode(), ex.getMessage()));
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // ThreadLocal 必须清理,否则线程复用会造成越权
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        // 兼容 EventSource 等无法自定义请求头的场景
        String parameter = request.getParameter("token");
        if (parameter != null && !parameter.isBlank()) {
            return parameter.trim();
        }
        // 反代场景:分享页前端的同源请求带不了 Authorization 头,只能靠 Cookie
        return AuthCookie.read(request);
    }
}
