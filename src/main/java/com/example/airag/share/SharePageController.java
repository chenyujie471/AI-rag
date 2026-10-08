package com.example.airag.share;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.airag.config.AppProperties;

import jakarta.servlet.http.HttpServletResponse;

/**
 * "地址栏伪装"分享页。
 *
 * <p>访问本控制器暴露的本地路径(默认 {@code /chat/share}),浏览器地址栏里始终是
 * 本服务的地址;FastGPT 分享页的真实地址只写在服务端配置
 * ({@code app.share.target-url})里,通过 iframe 内嵌展示。</p>
 *
 * <p><b>备用入口:</b>登录后的默认落地页已经改成我们自己的 {@code /faq.html} /
 * {@code /after-sale.html}(问答走 {@code /api/chat/*},配额与记账天然生效)。
 * 本页面只在"想直接给用户看 FastGPT 原版界面"时才用得上,配合
 * {@code deploy/nginx-airag.conf} 反代才能让配额生效。</p>
 *
 * <p>路径用 {@code ${app.share.page-path}} 注入,改配置即可换路径。</p>
 *
 * <p><b>注意:</b>这只是"看得见"层面的隐藏,不是安全边界 —— 打开开发者工具依然能看到
 * iframe 的真实 src,用户也可以直接访问 FastGPT。详见 README。</p>
 */
@RestController
public class SharePageController {

    private static final String TEMPLATE_PATH = "share/share-frame.html";

    private final AppProperties properties;
    private final ShareTargetProbe probe;

    /** 模板只在首次请求时读一次,之后复用。 */
    private volatile String template;

    public SharePageController(AppProperties properties, ShareTargetProbe probe) {
        this.properties = properties;
        this.probe = probe;
    }

    @GetMapping("${app.share.page-path:/chat/share}")
    public void sharePage(HttpServletResponse response) throws IOException {
        AppProperties.Share share = properties.share();
        if (!share.usable()) {
            // 没配置目标地址时不装作有这个页面,直接回到我们自己的问答页
            response.sendRedirect("/faq.html");
            return;
        }

        ShareTargetProbe.FramingCheck framing = probe.check(share.targetUrl());
        String reason = framing.reason().isBlank() ? "目标页面不允许被嵌入显示。" : framing.reason();

        String html = template()
                .replace("__TITLE__", escapeHtml(share.title()))
                .replace("__TARGET_URL__", escapeHtml(share.targetUrl()))
                .replace("__FRAME_HIDDEN__", framing.frameable() ? "" : "hidden")
                .replace("__FALLBACK_HIDDEN__", framing.frameable() ? "hidden" : "")
                .replace("__REASON__", escapeHtml(reason));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(html);
    }

    private String template() throws IOException {
        String cached = template;
        if (cached != null) {
            return cached;
        }
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        try (InputStream in = resource.getInputStream()) {
            cached = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        template = cached;
        return cached;
    }

    /** 目标地址来自配置,仍然做一次转义,避免配置里出现引号时破坏 HTML。 */
    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
