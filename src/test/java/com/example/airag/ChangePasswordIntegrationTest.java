package com.example.airag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.example.airag.auth.PasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助修改密码:核对原密码 + 新密码校验。
 *
 * <p>最关键的一条性质是<b>不需要令牌</b> —— 登录页上的入口是在登录之前用的,
 * 身份完全由"知道原密码"来证明。所以这里所有请求都不带 Authorization 头。</p>
 *
 * <p>这些用例都会改动同一个账号的密码,所以 {@link #restorePassword()} 在每个用例之后
 * 把密码强制复位 —— 否则用例之间会互相影响,单独跑和一起跑结果还不一样。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:airag_pwd;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.auth.redirect-url=/faq.html",
        "app.share.target-url=http://fastgpt.internal/chat/share?shareId=SECRET",
        "app.share.probe-frame-options=false"
})
class ChangePasswordIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "admin";
    private static final String OLD = "admin123";

    @AfterEach
    void restorePassword() {
        jdbcTemplate.update("UPDATE sys_user SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(OLD), USER);
    }

    /** 核心场景:不带令牌也能改,改完新密码能登、旧密码不能登。 */
    @Test
    void changePasswordWithoutTokenThenLoginWithNewOne() throws Exception {
        String fresh = "new-secret-9";

        HttpResponse<String> changed = changePwd(USER, OLD, fresh);
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(0, json.readTree(changed.body()).path("code").asInt());

        assertEquals(200, login(USER, fresh).statusCode(), "新密码应该能登录");
        assertEquals(401, login(USER, OLD).statusCode(), "旧密码应该已经失效");
    }

    @Test
    void rejectWrongOldPassword() throws Exception {
        HttpResponse<String> resp = changePwd(USER, "definitely-not-it", "whatever123");
        assertEquals(401, resp.statusCode(), resp.body());
        // 失败之后原密码必须仍然有效
        assertEquals(200, login(USER, OLD).statusCode());
    }

    @Test
    void rejectUnknownAccount() throws Exception {
        assertEquals(401, changePwd("no-such-user", "whatever", "whatever123").statusCode());
    }

    @Test
    void rejectTooShortNewPassword() throws Exception {
        HttpResponse<String> resp = changePwd(USER, OLD, "12345");
        assertEquals(400, resp.statusCode(), resp.body());
        assertTrue(json.readTree(resp.body()).path("message").asText().contains("6"), resp.body());
        assertEquals(200, login(USER, OLD).statusCode(), "校验失败不该动到密码");
    }

    @Test
    void rejectSameAsOldPassword() throws Exception {
        HttpResponse<String> resp = changePwd(USER, OLD, OLD);
        assertEquals(400, resp.statusCode(), resp.body());
        assertTrue(json.readTree(resp.body()).path("message").asText().contains("原密码"), resp.body());
    }

    @Test
    void rejectBlankFields() throws Exception {
        assertEquals(400, changePwd("", OLD, "whatever123").statusCode());
        assertEquals(400, changePwd(USER, "", "whatever123").statusCode());
        assertEquals(400, changePwd(USER, OLD, "").statusCode());
    }

    /** 改完密码之后,反复改也一直有效 —— 确认每次都用新的随机盐重新哈希,落库了。 */
    @Test
    void changeIsRepeatableAndPersisted() throws Exception {
        assertEquals(200, changePwd(USER, OLD, "persisted-1").statusCode());
        assertEquals(200, login(USER, "persisted-1").statusCode());
        assertEquals(200, changePwd(USER, "persisted-1", "persisted-2").statusCode());
        assertEquals(200, login(USER, "persisted-2").statusCode());
        assertEquals(401, login(USER, "persisted-1").statusCode());
    }

    // ------------------------------------------------------------------ 工具

    private HttpResponse<String> changePwd(String username, String oldPwd, String newPwd) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"oldPassword\":\"" + oldPwd
                + "\",\"newPassword\":\"" + newPwd + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base("/api/auth/password")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // 注意:不带 Authorization —— 这正是本接口的设计前提
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base("/api/auth/login")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String base(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
