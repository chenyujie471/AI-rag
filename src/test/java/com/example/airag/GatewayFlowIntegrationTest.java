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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端:真实起 Web 服务 + H2(MySQL 兼容模式)跑 schema.sql,不需要本机 MySQL。
 *
 * <p>FastGPT 侧用 JDK 自带的 {@link HttpServer} 起一个假的(见
 * {@link #pointGatewayAtFakeFastGpt}),所以既不需要真的上游,也不依赖任何模拟回复逻辑 ——
 * 网关那条"未配置就返回模拟内容"的降级分支已经删掉了。</p>
 *
 * <p>覆盖:登录 / 鉴权拦截 / 流式对话 / 两张记录表落库 /
 * 总额度扣减 / 每日渠道上限 / 被拦截请求记为 REJECTED。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:airag;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.quota.default-total=3",
        // 这些用例只关心"每条链路"的上限,把"每账号每日总量"关掉,免得两层互相干扰
        "app.quota.daily-limit.per-user=-1",
        "app.quota.daily-limit.faq=2",
        "app.quota.daily-limit.after-sale=-1",
        // 把跳转地址钉死,否则这个断言会跟着 application.yaml 里的部署默认值一起变
        "app.auth.redirect-url=/chat.html",
        // 分享页:用假域名 + 关掉响应头探测,避免测试里发起真实的外网请求
        "app.share.target-url=http://fastgpt.internal/chat/share?shareId=SECRET",
        "app.share.probe-frame-options=false"
})
class GatewayFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static HttpServer fakeFastGpt;

    /**
     * 假 FastGPT:监听一个随机端口,并把两个应用的 api-url / api-key 指过去。
     *
     * <p>必须走 {@link DynamicPropertySource},因为端口要等 {@link HttpServer} 起来之后才知道。</p>
     */
    @DynamicPropertySource
    static void pointGatewayAtFakeFastGpt(DynamicPropertyRegistry registry) throws IOException {
        fakeFastGpt = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastGpt.createContext("/", GatewayFlowIntegrationTest::handleFakeFastGpt);
        fakeFastGpt.start();
        String endpoint = "http://127.0.0.1:" + fakeFastGpt.getAddress().getPort()
                + "/api/v1/chat/completions";
        for (String app : new String[]{"faq", "after-sale"}) {
            registry.add("app.gateway." + app + ".api-url", () -> endpoint);
            registry.add("app.gateway." + app + ".api-key", () -> "fastgpt-integration-test");
            registry.add("app.gateway." + app + ".app-id", () -> "test-app-id");
        }
    }

    @AfterAll
    static void stopFakeFastGpt() {
        if (fakeFastGpt != null) {
            fakeFastGpt.stop(0);
        }
    }

    /** 按请求体里的 stream 决定回 SSE 还是整包 JSON。 */
    private static void handleFakeFastGpt(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.contains("\"stream\":false")) {
            byte[] payload = ("{\"id\":\"fake\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"你好,很高兴\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":5,\"total_tokens\":15}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
        // 0 = 长度未知,走 chunked,和真实 SSE 一致
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\",很高兴\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM ai_question_record");
        jdbc.update("DELETE FROM after_sale_question_record");
        jdbc.update("DELETE FROM usage_log");
        jdbc.update("UPDATE sys_user SET quota_used = 0, quota_total = 3");
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        HttpResponse<String> resp = post("/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"bad\"}");
        assertEquals(401, resp.statusCode());
        assertEquals(40100, json.readTree(resp.body()).path("code").asInt());
    }

    @Test
    void chatRequiresToken() throws Exception {
        HttpResponse<String> resp = post("/api/chat/faq", null, "{\"question\":\"hi\"}");
        assertEquals(401, resp.statusCode());
    }

    /**
     * 地址栏伪装:登录后跳的是本服务的 /chat/share,浏览器地址栏不会出现 FastGPT 的真实地址,
     * 真实地址只作为 iframe 的 src 存在于页面内部。
     */
    @Test
    void sharePageKeepsOurOwnUrlAndEmbedsFastGpt() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/share"))
                .GET().build();
        HttpResponse<String> page = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // 关键:直接返回页面(200),而不是 302 跳到 FastGPT
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.headers().firstValue("content-type").orElse("").startsWith("text/html"),
                page.headers().firstValue("content-type").orElse(""));

        String html = page.body();
        assertFalse(html.contains("__TARGET_URL__"), "模板占位符没被替换");
        assertFalse(html.contains("__FRAME_HIDDEN__"), "模板占位符没被替换");
        assertFalse(html.contains("__REASON__"), "模板占位符没被替换");
        assertTrue(html.contains("http://fastgpt.internal/chat/share?shareId=SECRET"),
                "真实地址应该出现在 iframe 里");
    }

    @Test
    void fullFlow_quotaDailyLimitAndRecords() throws Exception {
        // 1. 登录:跳转地址由 @TestPropertySource 钉成内置对话页
        HttpResponse<String> login = post("/api/auth/login", null,
                "{\"username\":\"admin\",\"password\":\"admin123\"}");
        assertEquals(200, login.statusCode(), login.body());
        JsonNode data = json.readTree(login.body()).path("data");
        String token = data.path("token").asText();
        assertEquals("/chat.html", data.path("redirectUrl").asText());
        assertEquals(3, data.path("quota").path("total").asInt());

        // 2. FAQ 两次成功(流式)
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> chat = post("/api/chat/faq", token, "{\"question\":\"怎么用\",\"stream\":true}");
            assertEquals(200, chat.statusCode(), chat.body());
            assertTrue(chat.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
            assertTrue(chat.body().contains("[DONE]"));
        }

        // 3. FAQ 第三次:触发每日上限 40302,不扣总额度
        HttpResponse<String> limited = post("/api/chat/faq", token, "{\"question\":\"再问一次\"}");
        assertEquals(403, limited.statusCode());
        assertEquals(40302, json.readTree(limited.body()).path("code").asInt());

        // 4. 售后不受 FAQ 每日上限影响:用掉最后 1 次总额度
        HttpResponse<String> as = post("/api/chat/after-sale", token, "{\"question\":\"保修多久\"}");
        assertEquals(200, as.statusCode(), as.body());

        // 5. 总额度耗尽 40301
        HttpResponse<String> exhausted = post("/api/chat/after-sale", token, "{\"question\":\"发票\"}");
        assertEquals(403, exhausted.statusCode());
        assertEquals(40301, json.readTree(exhausted.body()).path("code").asInt());

        // 6. 两张表分别落库,拦截的请求记为 REJECTED
        assertEquals(2, count("ai_question_record", "SUCCESS"));
        assertEquals(1, count("ai_question_record", "REJECTED"));
        assertEquals(1, count("after_sale_question_record", "SUCCESS"));
        assertEquals(1, count("after_sale_question_record", "REJECTED"));
        assertEquals(3, jdbc.queryForObject("SELECT quota_used FROM sys_user WHERE username='admin'", Integer.class));

        // 7. 账号使用账:两条链路共用一张表,3 次成功 + 2 次被拦各留一行
        assertEquals(3, count("usage_log", "SUCCESS"));
        assertEquals(2, count("usage_log", "REJECTED"));
        // 失败/被拦的那两次不占扣减次数
        assertEquals(0, jdbc.queryForObject(
                "SELECT cost_quota FROM usage_log WHERE status='REJECTED' ORDER BY id LIMIT 1", Integer.class));
        // 记到了来源(说明 ClientInfoResolver 真的从当前请求里取到了值)
        String clientIp = jdbc.queryForObject(
                "SELECT client_ip FROM usage_log ORDER BY id LIMIT 1", String.class);
        assertTrue(clientIp != null && !clientIp.isBlank(), "应该记到客户端 IP,实际: " + clientIp);
        String userAgent = jdbc.queryForObject(
                "SELECT user_agent FROM usage_log ORDER BY id LIMIT 1", String.class);
        assertTrue(userAgent != null && !userAgent.isBlank(), "应该记到 User-Agent,实际: " + userAgent);

        // 8. /api/auth/me 返回各链路今日用量
        HttpResponse<String> me = get("/api/auth/me", token);
        JsonNode daily = json.readTree(me.body()).path("data").path("daily");
        assertEquals("faq", daily.path(0).path("channel").asText());
        assertEquals(2, daily.path(0).path("used").asInt());
        assertEquals(0, daily.path(0).path("remaining").asInt());
        assertTrue(daily.path(1).path("unlimited").asBoolean());

        // 9. 记录查询接口
        HttpResponse<String> records = get("/api/records/faq?page=1&size=10", token);
        assertEquals(3, json.readTree(records.body()).path("data").path("total").asInt());

        // 10. 使用日志:两条链路合并,按时间倒序
        JsonNode logs = json.readTree(get("/api/logs?page=1&size=10", token).body()).path("data");
        assertEquals(5, logs.path("total").asInt());
        assertEquals("after-sale", logs.path("items").path(0).path("channel").asText());
        assertEquals("REJECTED", logs.path("items").path(0).path("status").asText());
        assertEquals("发票", logs.path("items").path(0).path("question").asText());

        // 11. 使用日志筛选:类型 + 状态 + 关键字 + 时间
        JsonNode faqOk = json.readTree(get("/api/logs?type=faq&status=SUCCESS", token).body()).path("data");
        assertEquals(2, faqOk.path("total").asInt());
        assertTrue(faqOk.path("items").path(0).path("answer").asText().length() > 0);
        assertEquals(1, json.readTree(get("/api/logs?keyword=%E4%BF%9D%E4%BF%AE", token).body())
                .path("data").path("total").asInt());
        assertEquals(0, json.readTree(get("/api/logs?start=2000-01-01T00:00&end=2000-01-02T00:00", token).body())
                .path("data").path("total").asInt());
        assertEquals(400, get("/api/logs?status=BOGUS", token).statusCode());

        // 12. 统计
        JsonNode stats = json.readTree(get("/api/logs/stats", token).body()).path("data");
        assertEquals(3, stats.path("faq").path("total").asInt());
        assertEquals(1, stats.path("faq").path("rejected").asInt());
        assertEquals(1, stats.path("after-sale").path("success").asInt());

        // 13. 个人信息带上 ID / 状态 / 时间
        JsonNode user = json.readTree(me.body()).path("data").path("user");
        assertTrue(user.path("id").asLong() > 0);
        assertEquals(1, user.path("status").asInt());
        assertFalse(user.path("createdAt").isMissingNode() || user.path("createdAt").isNull());
    }

    private int count(String table, String status) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE status = ?", Integer.class, status);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
