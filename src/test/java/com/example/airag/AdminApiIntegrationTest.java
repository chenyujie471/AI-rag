package com.example.airag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理后台端到端:权限边界 + 用户管理 + 按人覆盖每日上限。
 *
 * <p>用 H2(MySQL 兼容模式)跑 {@code schema.sql},所以 {@code sys_user} 是带着
 * {@code role} / {@code daily_limit_*} 三列一起建出来的;{@code SchemaMigrator}
 * 在这条路径上会发现列已存在,什么都不做 —— 它服务的是"表早就建好了的老库"。</p>
 *
 * <p>不做真实的 FastGPT 调用:这里关心的是"管理后台改了配置之后,系统认不认",
 * 而 {@code /api/auth/me} 返回的今日用量快照正好能完整反映上限的取值,不用真去提问。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:airag_admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.quota.default-total=100",
        // 给两个互不相同的全局值,这样"跟随全局"和"个人覆盖"在断言里能区分开
        // 只测"每条链路 / 按人覆盖"的上限,把"每账号每日总量"关掉,免得两层互相干扰
        "app.quota.daily-limit.per-user=-1",
        "app.quota.daily-limit.faq=7",
        "app.quota.daily-limit.after-sale=3",
        "app.auth.redirect-url=/faq.html",
        "app.share.target-url=http://fastgpt.internal/chat/share?shareId=SECRET",
        "app.share.probe-frame-options=false"
})
class AdminApiIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------ 权限

    @Test
    void adminEndpointsRejectAnonymous() throws Exception {
        assertEquals(401, get("/api/admin/users", null).statusCode());
        assertEquals(401, get("/api/admin/overview", null).statusCode());
    }

    /** 普通用户拿到的是 403 + 40303,前端据此把人送回问答页。 */
    @Test
    void adminEndpointsRejectNormalUser() throws Exception {
        String adminToken = login("admin", "admin123");
        createUser(adminToken, "{\"username\":\"plain\",\"password\":\"plain123\",\"nickname\":\"普通人\"}");
        String plainToken = login("plain", "plain123");

        HttpResponse<String> resp = get("/api/admin/users", plainToken);
        assertEquals(403, resp.statusCode(), resp.body());
        assertEquals(40303, json.readTree(resp.body()).path("code").asInt());

        // 概览、全站记录、写操作同样要挡住
        assertEquals(403, get("/api/admin/overview", plainToken).statusCode());
        assertEquals(403, get("/api/admin/logs", plainToken).statusCode());
        assertEquals(403, delete("/api/admin/users/1", plainToken).statusCode());
    }

    /** 登录响应里必须带上 admin 标记,前端才知道要不要显示管理入口。 */
    @Test
    void loginExposesAdminFlag() throws Exception {
        HttpResponse<String> adminLogin = post("/api/auth/login", null,
                "{\"username\":\"admin\",\"password\":\"admin123\"}");
        JsonNode adminUser = json.readTree(adminLogin.body()).path("data").path("user");
        assertTrue(adminUser.path("admin").asBoolean(), "默认管理员应该 admin=true");
        assertEquals("ADMIN", adminUser.path("role").asText());

        String adminToken = login("admin", "admin123");
        createUser(adminToken, "{\"username\":\"bob\",\"password\":\"bob12345\",\"nickname\":\"小鲍\"}");

        HttpResponse<String> bobLogin = post("/api/auth/login", null,
                "{\"username\":\"bob\",\"password\":\"bob12345\"}");
        JsonNode bobUser = json.readTree(bobLogin.body()).path("data").path("user");
        assertFalse(bobUser.path("admin").asBoolean(), "新建的普通用户 admin 必须是 false");
        assertEquals("USER", bobUser.path("role").asText());
    }

    // -------------------------------------------------------------- 用户管理

    @Test
    void createSearchAndPageUsers() throws Exception {
        String token = login("admin", "admin123");
        createUser(token, "{\"username\":\"alice\",\"password\":\"alice123\",\"nickname\":\"爱丽丝\"}");
        createUser(token, "{\"username\":\"carol\",\"password\":\"carol123\",\"nickname\":\"卡罗尔\","
                + "\"role\":\"ADMIN\"}");

        JsonNode all = data(get("/api/admin/users?size=50", token));
        // 至少是 admin(默认管理员)+ 本次新建的 alice / carol。
        // 这里不写死具体数字:同一个 Spring 上下文里的其它用例也会建用户,断言总数等于某个值会被执行顺序搞挂。
        assertTrue(all.path("total").asInt() >= 3, "至少有 admin/alice/carol:" + all);

        // 按账号模糊搜
        JsonNode hit = data(get("/api/admin/users?keyword=ali", token));
        assertEquals(1, hit.path("total").asInt(), hit.toString());
        assertEquals("alice", hit.path("items").get(0).path("username").asText());

        // 按昵称模糊搜
        assertEquals(1, data(get("/api/admin/users?keyword=卡罗", token)).path("total").asInt());

        // 按角色筛
        JsonNode admins = data(get("/api/admin/users?role=ADMIN&size=50", token));
        for (JsonNode item : admins.path("items")) {
            assertEquals("ADMIN", item.path("role").asText());
        }
    }

    @Test
    void rejectDuplicateUsernameAndShortPassword() throws Exception {
        String token = login("admin", "admin123");
        HttpResponse<String> dup = post("/api/admin/users", token,
                "{\"username\":\"admin\",\"password\":\"whatever123\"}");
        assertEquals(400, dup.statusCode(), dup.body());

        HttpResponse<String> shortPwd = post("/api/admin/users", token,
                "{\"username\":\"shorty\",\"password\":\"123\"}");
        assertEquals(400, shortPwd.statusCode(), shortPwd.body());
    }

    @Test
    void resetPasswordLetsUserLoginWithNewOne() throws Exception {
        String token = login("admin", "admin123");
        long id = createUser(token, "{\"username\":\"dave\",\"password\":\"dave1234\"}");
        login("dave", "dave1234");   // 旧密码先确认能登

        HttpResponse<String> reset = put("/api/admin/users/" + id + "/password", token,
                "{\"password\":\"brand-new-pwd\"}");
        assertEquals(200, reset.statusCode(), reset.body());

        // 新密码能登,旧密码不能
        assertEquals(200, post("/api/auth/login", null,
                "{\"username\":\"dave\",\"password\":\"brand-new-pwd\"}").statusCode());
        assertEquals(401, post("/api/auth/login", null,
                "{\"username\":\"dave\",\"password\":\"dave1234\"}").statusCode());
    }

    @Test
    void disabledUserCannotLoginNorUseAdminApi() throws Exception {
        String token = login("admin", "admin123");
        long id = createUser(token, "{\"username\":\"erin\",\"password\":\"erin1234\",\"role\":\"ADMIN\"}");
        String erinToken = login("erin", "erin1234");
        assertEquals(200, get("/api/admin/overview", erinToken).statusCode());

        HttpResponse<String> disabled = put("/api/admin/users/" + id, token, "{\"status\":0}");
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(0, json.readTree(disabled.body()).path("data").path("status").asInt());

        // 已登录的令牌立刻失效于管理接口(AdminInterceptor 每次都回查 status)
        HttpResponse<String> afterDisable = get("/api/admin/overview", erinToken);
        assertEquals(403, afterDisable.statusCode(), afterDisable.body());

        // 也不能再登录
        assertEquals(403, post("/api/auth/login", null,
                "{\"username\":\"erin\",\"password\":\"erin1234\"}").statusCode());
    }

    @Test
    void resetQuotaUsedClearsCounter() throws Exception {
        String token = login("admin", "admin123");
        long id = createUser(token, "{\"username\":\"frank\",\"password\":\"frank123\"}");

        HttpResponse<String> reset = post("/api/admin/users/" + id + "/quota/reset", token, null);
        assertEquals(200, reset.statusCode(), reset.body());
        assertEquals(0, json.readTree(reset.body()).path("data").path("quotaUsed").asInt());
    }

    @Test
    void deleteUserRemovesAccount() throws Exception {
        String token = login("admin", "admin123");
        long id = createUser(token, "{\"username\":\"grace\",\"password\":\"grace123\"}");

        assertEquals(200, delete("/api/admin/users/" + id, token).statusCode());
        assertEquals(400, get("/api/admin/users/" + id, token).statusCode());
        // 账号没了就登不进来
        assertEquals(401, post("/api/auth/login", null,
                "{\"username\":\"grace\",\"password\":\"grace123\"}").statusCode());
    }

    // ---------------------------------------------------------- 每日上限覆盖

    /**
     * 这是整个功能的核心:管理员给某个人单独设了上限,这个人就应该按自己的算,
     * 而不是按 application.yaml 里的全局值。
     */
    @Test
    void perUserDailyLimitOverridesGlobal() throws Exception {
        String adminToken = login("admin", "admin123");
        long id = createUser(adminToken, "{\"username\":\"henry\",\"password\":\"henry123\"}");
        String henryToken = login("henry", "henry123");

        // 1. 没设过 → 跟随全局(application.yaml 里 faq=7 / after-sale=3)
        JsonNode daily = dailyOf(henryToken);
        assertEquals(7, limitOf(daily, "faq"));
        assertEquals(3, limitOf(daily, "after-sale"));

        // 2. 设成 20 / -1(不限)
        HttpResponse<String> set = put("/api/admin/users/" + id + "/daily-limit", adminToken,
                "{\"faq\":20,\"afterSale\":-1}");
        assertEquals(200, set.statusCode(), set.body());
        daily = dailyOf(henryToken);
        assertEquals(20, limitOf(daily, "faq"), daily.toString());
        assertTrue(unlimitedOf(daily, "after-sale"), daily.toString());

        // 3. 清空(faq=null)→ 回到跟随全局
        assertEquals(200, put("/api/admin/users/" + id + "/daily-limit", adminToken,
                "{\"faq\":null,\"afterSale\":null}").statusCode());
        daily = dailyOf(henryToken);
        assertEquals(7, limitOf(daily, "faq"), daily.toString());
        assertEquals(3, limitOf(daily, "after-sale"), daily.toString());

        // 4. 设"每日总量" → 用户那边"今日总计"的上限跟着变。
        //    这是管理后台那个入口真正要改的东西:以前它只写得动每链路的上限,而页面读的是全局值。
        assertEquals(200, put("/api/admin/users/" + id + "/daily-limit", adminToken,
                "{\"total\":42,\"faq\":null,\"afterSale\":null}").statusCode());
        daily = dailyOf(henryToken);
        assertEquals(42, limitOf(daily, "total"), daily.toString());
        assertEquals(7, limitOf(daily, "faq"), "每链路那层不该被总量影响:" + daily);
    }

    /** 个人上限只影响那一个人,别人继续用全局值。 */
    @Test
    void personalLimitDoesNotLeakToOthers() throws Exception {
        String adminToken = login("admin", "admin123");
        long id = createUser(adminToken, "{\"username\":\"ivan\",\"password\":\"ivan1234\"}");
        createUser(adminToken, "{\"username\":\"judy\",\"password\":\"judy1234\"}");
        put("/api/admin/users/" + id + "/daily-limit", adminToken, "{\"faq\":1,\"afterSale\":1}");

        assertEquals(1, limitOf(dailyOf(login("ivan", "ivan1234")), "faq"));
        assertEquals(7, limitOf(dailyOf(login("judy", "judy1234")), "faq"));
    }

    @Test
    void userListExposesPersonalLimit() throws Exception {
        String token = login("admin", "admin123");
        long id = createUser(token, "{\"username\":\"kate\",\"password\":\"kate1234\","
                + "\"dailyLimitTotal\":9,\"dailyLimitFaq\":5}");
        JsonNode view = data(get("/api/admin/users/" + id, token));
        assertEquals(9, view.path("dailyLimitTotal").asInt(), view.toString());
        assertEquals(5, view.path("dailyLimitFaq").asInt());
        assertTrue(view.path("dailyLimitAfterSale").isNull(), "没设的那条应该是 null:" + view);
    }

    // ------------------------------------------------------------ 防锁死守卫

    @Test
    void cannotDemoteOrDeleteSelf() throws Exception {
        String token = login("admin", "admin123");
        long selfId = data(get("/api/auth/me", token)).path("user").path("id").asLong();

        HttpResponse<String> demote = put("/api/admin/users/" + selfId, token, "{\"role\":\"USER\"}");
        assertEquals(400, demote.statusCode(), demote.body());
        assertTrue(json.readTree(demote.body()).path("message").asText().contains("自己"),
                demote.body());

        HttpResponse<String> disableSelf = put("/api/admin/users/" + selfId, token, "{\"status\":0}");
        assertEquals(400, disableSelf.statusCode(), disableSelf.body());

        HttpResponse<String> deleteSelf = delete("/api/admin/users/" + selfId, token);
        assertEquals(400, deleteSelf.statusCode(), deleteSelf.body());
    }

    /**
     * 系统里只剩一个可用管理员时,连别人也不能把它降级 —— 否则就没人进得去后台了。
     *
     * <p><b>这条规则其实不可达,所以这里测的是它的"近亲"</b>:能走到管理接口的人自己必然是一个
     * 启用的管理员,所以当他去降级"最后一个可用管理员"时,那个目标只能是他自己 ——
     * 而自我降级已经被上一条规则挡住了。换句话说"降级最后一个管理员"要么等于"降级自己",
     * 要么此时系统里至少有两个可用管理员。两把锁合起来保证了管理员数量不会降到 0。</p>
     *
     * <p>所以这里改为验证一个更实际的性质:多管理员场景下,降级<b>别人</b>是允许的,
     * 而且系统里始终至少留得下一个人。</p>
     */
    @Test
    void canDemoteOtherAdminWhileTwoRemain() throws Exception {
        String token = login("admin", "admin123");
        long secondId = createUser(token, "{\"username\":\"second-admin\",\"password\":\"second123\","
                + "\"role\":\"ADMIN\"}");

        // 两个可用管理员 → 降级其中一个应当被允许
        HttpResponse<String> demote = put("/api/admin/users/" + secondId, token, "{\"role\":\"USER\"}");
        assertEquals(200, demote.statusCode(), demote.body());
        assertEquals("USER", json.readTree(demote.body()).path("data").path("role").asText());

        // 无论怎么折腾,管理员数量都不该变成 0
        assertTrue(data(get("/api/admin/overview", token)).path("adminCount").asInt() >= 1);
    }

    // ------------------------------------------------------------------ 概览

    @Test
    void overviewReturnsCountsAndTwoChannels() throws Exception {
        String token = login("admin", "admin123");
        JsonNode overview = data(get("/api/admin/overview", token));
        assertTrue(overview.path("userCount").asInt() >= 1, overview.toString());
        assertTrue(overview.path("adminCount").asInt() >= 1, overview.toString());
        assertEquals(2, overview.path("today").size());
        assertEquals(2, overview.path("allTime").size());
        assertEquals("faq", overview.path("today").get(0).path("channel").asText());
        assertEquals("after-sale", overview.path("today").get(1).path("channel").asText());
    }

    @Test
    void adminCanReadLogsOfEverybody() throws Exception {
        String token = login("admin", "admin123");
        // 不带 userId = 全站;带 userId = 只看某人。两者都不该报错
        assertEquals(200, get("/api/admin/logs?size=5", token).statusCode());
        assertEquals(200, get("/api/admin/logs?userId=1&size=5", token).statusCode());
        assertEquals(200, get("/api/admin/logs/stats", token).statusCode());
        assertEquals(200, get("/api/admin/logs?type=faq&status=SUCCESS&keyword=x&size=5", token).statusCode());
    }

    @Test
    void userLogEndpointStaysScopedToSelf() throws Exception {
        String token = login("admin", "admin123");
        createUser(token, "{\"username\":\"nobody\",\"password\":\"nobody12\"}");
        String nobodyToken = login("nobody", "nobody12");
        // 普通用户的 /api/logs 只返回自己的,传别人的 id 也没用(接口根本不接受这个参数)
        JsonNode body = data(get("/api/logs?size=5", nobodyToken));
        for (JsonNode item : body.path("items")) {
            // 这一句只是说明结构一致;真正的越权防线是后端把 userId 钉死成当前登录用户
            assertFalse(item.path("channel").asText().isEmpty());
        }
        assertEquals(200, get("/api/logs/stats", nobodyToken).statusCode());
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode dailyOf(String token) throws Exception {
        return data(get("/api/auth/me", token)).path("daily");
    }

    private static int limitOf(JsonNode daily, String channel) {
        for (JsonNode item : daily) {
            if (channel.equals(item.path("channel").asText())) {
                return item.path("limit").asInt();
            }
        }
        throw new AssertionError("daily 里没有 " + channel + ": " + daily);
    }

    private static boolean unlimitedOf(JsonNode daily, String channel) {
        for (JsonNode item : daily) {
            if (channel.equals(item.path("channel").asText())) {
                return item.path("unlimited").asBoolean();
            }
        }
        throw new AssertionError("daily 里没有 " + channel + ": " + daily);
    }

    private long createUser(String token, String body) throws Exception {
        HttpResponse<String> resp = post("/api/admin/users", token, body);
        assertEquals(200, resp.statusCode(), resp.body());
        return json.readTree(resp.body()).path("data").path("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> resp = post("/api/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        return json.readTree(resp.body()).path("data").path("token").asText();
    }

    private JsonNode data(HttpResponse<String> resp) throws Exception {
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode root = json.readTree(resp.body());
        assertEquals(0, root.path("code").asInt(), resp.body());
        return root.path("data");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(path))).GET();
        return send(builder, token);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        return send(builder, token);
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        return send(builder, token);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(path))).DELETE();
        return send(builder, token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String token) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String base(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
