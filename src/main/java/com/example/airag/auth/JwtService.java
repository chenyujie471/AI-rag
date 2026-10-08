package com.example.airag.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 极简 JWT(HS256)实现,只用 JDK 的 HMAC + Base64URL,不引入 jjwt 等第三方库。
 *
 * <p>令牌结构:{@code base64url(header).base64url(payload).base64url(hmacSha256)}</p>
 */
@Component
public class JwtService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int TOKEN_PART_COUNT = 3;

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.secret = properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = properties.auth().tokenTtlMinutes() * 60L;
    }

    /** 签发令牌。 */
    public String issue(long userId, String username) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(userId));
        claims.put("username", username);
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);
        try {
            String header = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String payload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "." + URL_ENCODER.encodeToString(hmac(signingInput));
        } catch (Exception ex) {
            throw new IllegalStateException("签发登录令牌失败", ex);
        }
    }

    /**
     * 校验令牌并返回载荷。
     *
     * @throws BizException 令牌缺失 / 格式错误 / 签名不通过 / 已过期
     */
    public JwtPayload verify(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.tokenInvalid("缺少登录令牌,请先登录");
        }
        String[] parts = token.split("\\.");
        if (parts.length != TOKEN_PART_COUNT) {
            throw BizException.tokenInvalid("登录令牌格式错误");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] actual;
        try {
            actual = URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw BizException.tokenInvalid("登录令牌格式错误");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw BizException.tokenInvalid("登录令牌签名校验失败");
        }
        try {
            JsonNode claims = objectMapper.readTree(URL_DECODER.decode(parts[1]));
            long exp = claims.path("exp").asLong(0L);
            if (exp <= Instant.now().getEpochSecond()) {
                throw BizException.tokenExpired("登录已过期,请重新登录");
            }
            long userId = Long.parseLong(claims.path("sub").asText("0"));
            if (userId <= 0L) {
                throw BizException.tokenInvalid("登录令牌内容无效");
            }
            return new JwtPayload(userId, claims.path("username").asText(""), exp);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.tokenInvalid("登录令牌解析失败");
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("登录令牌签名失败", ex);
        }
    }

    /** 令牌载荷。 */
    public record JwtPayload(long userId, String username, long expiresAtEpochSecond) {
    }
}
