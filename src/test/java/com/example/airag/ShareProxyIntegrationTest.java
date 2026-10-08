package com.example.airag;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享页反代拦截的端到端测试。
 *
 * <p>不需要真的 FastGPT:用 JDK 自带的 {@link HttpServer} 起一个假的,按 SSE 回几句话,
 * 并且把收到的请求头/请求体留下来给断言用。</p>
 *
 * <p>覆盖:登录种下 HttpOnly Cookie → 仅凭 Cookie(无 Authorization 头)即可通过鉴权 →
 * 对话请求被记账 → chatId 被加上账号前缀 → 登录 Cookie 不会被泄露给上游 →
 * 每日上限到点后拒绝。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:airag_share;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        // 总额度给足,让每日上限成为唯一的限制条件
        "app.quota.default-total=100",
        // 只测反代链路的每日上限,把"每账号每日总量"关掉,免得两层互相干扰
        "app.quota.daily-limit.per-user=-1",
        "app.quota.daily-limit.faq=2",
        "app.quota.daily-limit.after-sale=-1",
        "app.auth.redirect-url=/chat.html",
        "app.share.probe-frame-options=false",
        // 打开反代拦截;upstream-base 由 @DynamicPropertySource 指向假 FastGPT
        "app.share-proxy.enabled=true",
        "app.share-proxy.channel=faq",
        "app.share-proxy.store-content=true",
        "app.share-proxy.namespace-chat-id=true"
})
class ShareProxyIntegrationTest {

    private static final String CHAT_PATH = "/api/v1/chat/completions";

    private static HttpServer fakeFastGpt;
    private static volatile String lastPath;
    private static volatile String lastBody;
    private static volatile String lastCookieHeader;
    private static volatile String lastAuthorizationHeader;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void pointProxyAtFakeFastGpt(DynamicPropertyRegistry registry) throws IOException {
        fakeFastGpt = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastGpt.createContext("/", ShareProxyIntegrationTest::handle);
        fakeFastGpt.start();
        registry.add("app.share-proxy.upstream-base",
                () -> "http://127.0.0.1:" + fakeFastGpt.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeFastGpt() {
        if (fakeFastGpt != null) {
            fakeFastGpt.stop(0);
        }
    }

    /** 假 FastGPT:按请求体里的 stream 回 SSE 或整包 JSON,并把收到的请求留档供断言。 */
    private static void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastCookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");

        boolean streaming = !lastBody.contains("\"stream\":false");
        if (streaming) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
            // 0 = 长度未知,走 chunked,和真实 SSE 一致
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\",很高兴\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.write("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return;
        }

        byte[] payload = ("{\"id\":\"fake\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"你好,很高兴\"}}],\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":5,\"total_tokens\":15}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM ai_question_record");
        jdbc.update("DELETE FROM after_sale_question_record");
        jdbc.update("DELETE FROM usage_log");
        jdbc.update("UPDATE sys_user SET quota_used = 0, quota_total = 100");
        lastPath = null;
        lastBody = null;
        lastCookieHeader = null;
        lastAuthorizationHeader = null;
    }

    @Test
    void chatPathWithoutLoginIsRejected() throws Exception {
        HttpResponse<String> anonymous = postWithCookie(CHAT_PATH, null, "{\"shareId\":\"SECRET\"}");
        assertEquals(401, anonymous.statusCode(), anonymous.body());

        HttpResponse<String> forged = postWithCookie(CHAT_PATH, "airag_token=not-a-real-jwt", "{\"shareId\":\"SECRET\"}");
        assertEquals(401, forged.statusCode(), forged.body());
    }

    /**
     * 主流程:分享页前端只会带 Cookie(它不认识我们的 JWT,也没法加 Authorization 头),
     * 网关必须仅凭 Cookie 就认出账号,并把这次对话算在该账号头上。
     */
    @Test
    void shareProxyCountsUsageAndStopsAtDailyLimit() throws Exception {
        String cookiePair = loginAndTakeCookiePair();

        // 1. 第一次:只带 Cookie,走被拦截的对话路径
        HttpResponse<String> first = chatWithCookie(cookiePair, "abc");
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"),
                "SSE 的 Content-Type 应该原样透传,实际: " + first.headers().firstValue("content-type").orElse(""));
        assertTrue(first.body().contains("你好"), "上游内容应原样透传,实际: " + first.body());
        assertTrue(first.body().contains("[DONE]"), first.body());

        // 2. 上游确实收到了请求,且路径与 shareId 原样保留
        assertNotNull(lastBody, "上游没收到请求");
        assertTrue(lastPath.startsWith(CHAT_PATH), "上游收到的路径: " + lastPath);
        JsonNode forwarded = json.readTree(lastBody);
        assertEquals("SECRET", forwarded.path("shareId").asText(), lastBody);

        // 3. 关键:chatId 被加上了账号前缀,保证每个账号在 FastGPT 侧会话隔离
        String forwardedChatId = forwarded.path("chatId").asText();
        assertTrue(forwardedChatId.startsWith("u1-faq-"), "chatId 应带账号前缀,实际: " + forwardedChatId);
        assertTrue(forwardedChatId.endsWith("abc"), "原始 chatId 应保留,实际: " + forwardedChatId);

        // 4. 安全:我们自己的登录 Cookie 绝不能送给上游
        assertTrue(lastCookieHeader == null || !lastCookieHeader.contains("airag_token"),
                "不应把登录 Cookie 透传给上游,实际: " + lastCookieHeader);
        assertTrue(lastAuthorizationHeader == null,
                "本就不该透传 Authorization,实际: " + lastAuthorizationHeader);

        // 5. 记账:内容表与 usage_log 各落一行,回答与 token 都要旁路解析出来
        assertEquals(1, count("ai_question_record", "SUCCESS"));
        assertEquals(1, count("usage_log", "SUCCESS"));
        assertEquals("你好,很高兴", jdbc.queryForObject(
                "SELECT answer FROM ai_question_record ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(15, jdbc.queryForObject(
                "SELECT total_tokens FROM usage_log ORDER BY id DESC LIMIT 1", Integer.class));
        assertEquals("faq", jdbc.queryForObject(
                "SELECT channel FROM usage_log ORDER BY id DESC LIMIT 1", String.class));

        // 6. 第二次:到达每日上限(配置为 2)之前的最后一次,应当放行
        assertEquals(200, chatWithCookie(cookiePair, "def").statusCode());

        // 7. 第三次:触发每日上限 40302
        HttpResponse<String> limited = chatWithCookie(cookiePair, "ghi");
        assertEquals(403, limited.statusCode(), limited.body());
        assertEquals(40302, json.readTree(limited.body()).path("code").asInt());
        assertTrue(limited.body().contains("今日提问次数已达上限"), limited.body());

        // 8. 被拦的那次仍然留痕(usage_log 记 REJECTED、不占扣减次数),
        //    但不会再往内容表里写一条 SUCCESS
        assertEquals(2, count("ai_question_record", "SUCCESS"));
        assertEquals(2, count("usage_log", "SUCCESS"));
        assertEquals(1, count("usage_log", "REJECTED"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT cost_quota FROM usage_log WHERE status='REJECTED' ORDER BY id DESC LIMIT 1", Integer.class));
    }

    /** 非流式响应(整包 JSON)也要能原样透传,并旁路解析出回答与 usage。 */
    @Test
    void shareProxyPassesThroughNonStreamingResponse() throws Exception {
        String cookiePair = loginAndTakeCookiePair();
        String body = "{\"shareId\":\"SECRET\",\"chatId\":\"abc\",\"stream\":false,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"怎么用\"}]}";
        HttpResponse<String> response = postWithCookie(CHAT_PATH, cookiePair, body);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"),
                "整包 JSON 的 Content-Type 应原样透传,实际: "
                        + response.headers().firstValue("content-type").orElse(""));
        assertTrue(response.body().contains("你好,很高兴"), "上游内容应原样透传,实际: " + response.body());

        // 旁路解析出来的回答与 usage 都要落库
        assertEquals("你好,很高兴", jdbc.queryForObject(
                "SELECT answer FROM ai_question_record ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(15, jdbc.queryForObject(
                "SELECT total_tokens FROM usage_log ORDER BY id DESC LIMIT 1", Integer.class));
        assertEquals(10, jdbc.queryForObject(
                "SELECT prompt_tokens FROM usage_log ORDER BY id DESC LIMIT 1", Integer.class));
    }

    // ------------------------------------------------------------------ 工具

    private String loginAndTakeCookiePair() throws Exception {
        HttpResponse<String> login = postJson("/api/auth/login", null, null,
                "{\"username\":\"admin\",\"password\":\"admin123\"}");
        assertEquals(200, login.statusCode(), login.body());

        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("airag_token="), "登录应种下登录 Cookie,实际: " + setCookie);
        assertTrue(setCookie.contains("HttpOnly"), "登录 Cookie 必须是 HttpOnly,实际: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"),
                "SameSite=Lax 是防 CSRF 的关键,不能省,实际: " + setCookie);
        return setCookie.split(";", 2)[0];
    }

    private HttpResponse<String> chatWithCookie(String cookiePair, String chatId) throws Exception {
        String body = "{\"shareId\":\"SECRET\",\"chatId\":\"" + chatId + "\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"怎么用\"}]}";
        return postWithCookie(CHAT_PATH, cookiePair, body);
    }

    /** 刻意不带 Authorization 头,只带 Cookie —— 模拟分享页前端的真实行为。 */
    private HttpResponse<String> postWithCookie(String path, String cookiePair, String body) throws Exception {
        return postJson(path, cookiePair, null, body);
    }

    private HttpResponse<String> postJson(String path, String cookiePair, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookiePair != null) {
            builder.header("Cookie", cookiePair);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int count(String table, String status) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE status = ?", Integer.class, status);
    }
}
