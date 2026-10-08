package com.example.airag.ratelimit;

import java.io.IOException;

import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.ErrorCode;
import com.example.airag.common.JsonResponseWriter;
import com.example.airag.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 频率限制拦截器。
 *
 * <p>限流维度:已登录按用户 ID,未登录按客户端 IP;同一规则共用同一个计数桶。
 * 阈值全部来自 {@code app.rate-limit.*},改 yaml 即可调整,无需改代码。</p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final JsonResponseWriter responseWriter;

    public RateLimitInterceptor(RateLimiter rateLimiter, AppProperties properties, JsonResponseWriter responseWriter) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.responseWriter = responseWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        AppProperties.RateLimit config = properties.rateLimit();
        if (!config.enabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        AppProperties.RateLimit.Rule rule = resolveRule(config, path);
        int limit = rule == null ? config.defaultLimit() : rule.limit();
        if (limit <= 0) {
            return true;
        }
        long windowSeconds = rule == null || rule.windowSeconds() == null || rule.windowSeconds() <= 0
                ? config.windowSeconds()
                : rule.windowSeconds();
        String scope = rule == null ? "*" : rule.path();

        RateLimitResult result = rateLimiter.tryAcquire(identity(request) + "|" + scope, limit, windowSeconds);
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        if (result.allowed()) {
            return true;
        }

        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        log.warn("触发频率限制: scope={} path={} 上限={}/{}秒", scope, path, limit, windowSeconds);
        responseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS,
                ApiResponse.fail(ErrorCode.TOO_MANY_REQUESTS,
                        "请求过于频繁,请 " + result.retryAfterSeconds() + " 秒后再试"));
        return false;
    }

    /** 取最后一条命中的规则,yaml 中越靠后优先级越高。 */
    private AppProperties.RateLimit.Rule resolveRule(AppProperties.RateLimit config, String path) {
        AppProperties.RateLimit.Rule matched = null;
        for (AppProperties.RateLimit.Rule rule : config.rules()) {
            if (rule.path() != null && !rule.path().isBlank() && PathPatternMatcher.matches(rule.path(), path)) {
                matched = rule;
            }
        }
        return matched;
    }

    private String identity(HttpServletRequest request) {
        LoginUser user = UserContext.get();
        return user != null ? "u:" + user.id() : "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
