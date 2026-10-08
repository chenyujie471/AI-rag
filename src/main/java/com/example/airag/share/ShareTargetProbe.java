package com.example.airag.share;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.airag.config.AppProperties;

/**
 * 探测 FastGPT 分享页是否允许被 iframe 嵌入。
 *
 * <p>嵌入失败时浏览器只会留一片空白,排查起来很费劲,所以这里主动读一次响应头:
 * {@code X-Frame-Options: DENY/SAMEORIGIN} 或 {@code CSP frame-ancestors 'none'/'self'}
 * 都意味着无法内嵌,此时页面会给出明确提示而不是白屏。</p>
 *
 * <p>只读响应头,拿到就断开连接,不下载整页;结果缓存 {@value #CACHE_TTL_MILLIS} 毫秒,
 * 避免每次打开页面都去打一次上游。探测失败(网络不通等)不影响使用,按"可以嵌入"处理,
 * 最终能不能显示交给浏览器决定。</p>
 *
 * <p>可用 {@code app.share.probe-frame-options=false} 关掉这个探测(离线环境/单元测试)。</p>
 */
@Component
public class ShareTargetProbe {

    private static final Logger log = LoggerFactory.getLogger(ShareTargetProbe.class);

    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * @param frameable 是否允许被嵌入
     * @param reason    不允许时的原因,仅用于页面提示
     */
    public record FramingCheck(boolean frameable, String reason) {
    }

    private record Cached(FramingCheck check, long checkedAt) {
    }

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ShareTargetProbe(AppProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FramingCheck check(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank() || !properties.share().probeFrameOptions()) {
            return new FramingCheck(true, "");
        }
        if (!isAbsolute(targetUrl)) {
            // 相对地址 = Nginx 反代模式:被嵌入的页面同样由我们自己这个源提供,
            // 同源策略天然满足,X-Frame-Options/CSP 都不会拦。不必也探测不了。
            return new FramingCheck(true, "");
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(targetUrl);
        if (cached != null && now - cached.checkedAt() < CACHE_TTL_MILLIS) {
            return cached.check();
        }
        FramingCheck fresh = probe(targetUrl);
        cache.put(targetUrl, new Cached(fresh, now));
        return fresh;
    }

    private static boolean isAbsolute(String url) {
        return url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8);
    }

    private FramingCheck probe(String targetUrl) {
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "AiRagShareProbe/1.0")
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FramingCheck(true, "");
        } catch (Exception e) {
            log.warn("探测分享页是否可嵌入失败,按可嵌入处理: {} ({})", targetUrl, e.toString());
            return new FramingCheck(true, "");
        }

        try {
            // 只需要响应头
            response.body().close();
        } catch (IOException ignored) {
            // 断开失败不影响判断
        }

        String xFrameOptions = response.headers().firstValue("x-frame-options").orElse("").trim();
        if (!xFrameOptions.isBlank()) {
            String value = xFrameOptions.toLowerCase(Locale.ROOT);
            if (value.startsWith("deny") || value.startsWith("sameorigin")) {
                return blocked(targetUrl, "FastGPT 返回 X-Frame-Options: " + xFrameOptions);
            }
        }

        String csp = response.headers().firstValue("content-security-policy").orElse("");
        String ancestors = frameAncestors(csp);
        if (ancestors.startsWith("'none'") || ancestors.startsWith("'self'")) {
            return blocked(targetUrl, "FastGPT 返回 CSP frame-ancestors " + ancestors);
        }

        log.info("分享页允许被嵌入: {}", targetUrl);
        return new FramingCheck(true, "");
    }

    /** 从 CSP 里取出 frame-ancestors 的取值,没有则返回空串。 */
    private static String frameAncestors(String csp) {
        if (csp == null || csp.isBlank()) {
            return "";
        }
        String lower = csp.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("frame-ancestors");
        if (index < 0) {
            return "";
        }
        String value = lower.substring(index + "frame-ancestors".length());
        int end = value.indexOf(';');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        return value.trim();
    }

    private static FramingCheck blocked(String targetUrl, String reason) {
        log.info("分享页不允许被嵌入,页面将给出提示: {} ({})", targetUrl, reason);
        return new FramingCheck(false, reason);
    }
}
