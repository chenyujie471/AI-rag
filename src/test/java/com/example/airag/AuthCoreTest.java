package com.example.airag;

import com.example.airag.auth.JwtService;
import com.example.airag.auth.PasswordEncoder;
import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鉴权核心逻辑的纯单元测试:不需要 Spring 容器,也不需要数据库。
 */
class AuthCoreTest {

    /** 六个参数全传 null,紧凑构造器会补齐安全默认值。 */
    private final AppProperties properties = new AppProperties(null, null, null, null, null, null, null);
    private final PasswordEncoder passwordEncoder = new PasswordEncoder();
    private final JwtService jwtService = new JwtService(new ObjectMapper(), properties);

    @Test
    void passwordHashShouldVerify() {
        String hash = passwordEncoder.encode("admin123");
        assertTrue(hash.startsWith("pbkdf2$"), "哈希应带算法前缀: " + hash);
        assertTrue(passwordEncoder.matches("admin123", hash));
    }

    @Test
    void wrongPasswordShouldFail() {
        String hash = passwordEncoder.encode("admin123");
        assertFalse(passwordEncoder.matches("admin124", hash));
    }

    @Test
    void samePasswordShouldProduceDifferentHashes() {
        assertNotEquals(passwordEncoder.encode("admin123"), passwordEncoder.encode("admin123"),
                "随机盐必须让两次哈希结果不同");
    }

    @Test
    void malformedHashShouldNotThrow() {
        assertFalse(passwordEncoder.matches("admin123", "not-a-valid-hash"));
        assertFalse(passwordEncoder.matches("admin123", null));
    }

    @Test
    void tokenShouldRoundTrip() {
        String token = jwtService.issue(42L, "admin");
        JwtService.JwtPayload payload = jwtService.verify(token);
        assertEquals(42L, payload.userId());
        assertEquals("admin", payload.username());
    }

    @Test
    void tamperedTokenShouldBeRejected() {
        String token = jwtService.issue(42L, "admin");
        String[] parts = token.split("\\.");
        // 改掉 payload 里的一个字符,签名就不再匹配
        char first = parts[1].charAt(0);
        char replaced = first == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + replaced + parts[1].substring(1) + "." + parts[2];
        assertThrows(BizException.class, () -> jwtService.verify(tampered));
    }

    @Test
    void garbageTokenShouldBeRejected() {
        assertThrows(BizException.class, () -> jwtService.verify("abc.def"));
        assertThrows(BizException.class, () -> jwtService.verify(""));
        assertThrows(BizException.class, () -> jwtService.verify(null));
    }

    @Test
    void tokenSignedWithAnotherSecretShouldBeRejected() {
        AppProperties other = new AppProperties(
                new AppProperties.Auth("a-completely-different-secret-value-32bytes", 60L, "", null),
                null, null, null, null, null, null);
        String foreignToken = new JwtService(new ObjectMapper(), other).issue(42L, "admin");
        assertThrows(BizException.class, () -> jwtService.verify(foreignToken));
    }
}
