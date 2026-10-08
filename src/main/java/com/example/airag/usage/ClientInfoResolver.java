package com.example.airag.usage;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取当前请求的客户端 IP 与 User-Agent,给 usage_log 做审计用。
 *
 * <p>从 {@link RequestContextHolder} 拿当前请求,而不是把 {@code HttpServletRequest}
 * 一层层往下传 —— 记账是横切关注点,不该污染对话主流程的方法签名。</p>
 *
 * <p>取不到时(非 Web 线程、单元测试里直接调)返回空值,绝不抛异常影响主流程。
 * 两个字段都会按列宽截断,避免超长 User-Agent 把插入搞失败。</p>
 */
@Component
public class ClientInfoResolver {

    /** 与 usage_log.client_ip 列宽一致 */
    private static final int MAX_IP_LENGTH = 64;
    /** 与 usage_log.user_agent 列宽一致 */
    private static final int MAX_USER_AGENT_LENGTH = 255;

    /** @param ip 客户端 IP;@param userAgent 原始 User-Agent(已截断) */
    public record ClientInfo(String ip, String userAgent) {

        public static final ClientInfo EMPTY = new ClientInfo(null, null);
    }

    public ClientInfo resolve() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes)) {
                return ClientInfo.EMPTY;
            }
            HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
            return new ClientInfo(
                    truncate(clientIp(request), MAX_IP_LENGTH),
                    truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH));
        } catch (Exception ex) {
            // 只是审计信息,拿不到就算了
            return ClientInfo.EMPTY;
        }
    }

    /**
     * 部署在 Nginx 之类的反向代理后面时,{@code getRemoteAddr()} 只会拿到代理地址,
     * 所以优先取 {@code X-Forwarded-For} 的第一段(最靠近客户端的那一跳)。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
