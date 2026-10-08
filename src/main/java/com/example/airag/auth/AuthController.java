package com.example.airag.auth;

import com.example.airag.auth.dto.ChangePasswordRequest;
import com.example.airag.auth.dto.LoginRequest;
import com.example.airag.auth.dto.LoginResponse;
import com.example.airag.auth.dto.ProfileResponse;
import com.example.airag.auth.dto.UserProfile;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import com.example.airag.quota.DailyLimitService;
import com.example.airag.quota.QuotaService;
import com.example.airag.user.User;
import com.example.airag.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 / 登出 / 当前用户。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final QuotaService quotaService;
    private final AppProperties properties;
    private final DailyLimitService dailyLimitService;

    public AuthController(UserService userService, JwtService jwtService, QuotaService quotaService,
                          AppProperties properties, DailyLimitService dailyLimitService) {
        this.dailyLimitService = dailyLimitService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.quotaService = quotaService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody(required = false) LoginRequest request,
                                            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (request == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        User user = userService.authenticate(request.getUsername(), request.getPassword());
        String token = jwtService.issue(user.getId(), user.getUsername());
        // 除了返回给前端存 localStorage,再种一份 HttpOnly Cookie:
        // 反代到我们源上的 FastGPT 分享页发同源请求时,只能靠它来识别账号。
        AuthCookie.write(httpRequest, httpResponse, token, jwtService.ttlSeconds());
        LoginResponse body = new LoginResponse(
                token,
                "Bearer",
                jwtService.ttlSeconds(),
                UserProfile.from(user),
                QuotaService.toInfo(user),
                resolveRedirectUrl());
        return ApiResponse.ok(body);
    }

    @GetMapping("/me")
    public ApiResponse<ProfileResponse> me() {
        LoginUser current = UserContext.require();
        User user = userService.requireById(current.id());
        return ApiResponse.ok(new ProfileResponse(
                UserProfile.from(user),
                QuotaService.toInfo(user),
                dailyLimitService.usageAll(user.getId()),
                resolveRedirectUrl()));
    }

    /**
     * 自助修改密码:核对原密码后写入新密码。
     *
     * <p><b>这个接口在拦截器里是放行的</b>(见 {@code WebConfig}),因为登录页上的入口
     * 是在"还没登录"的状态下用的 —— 身份完全由原密码证明。所以它和登录接口一样是可以被
     * 用来猜密码的,限流规则里给了和登录同级的限制。</p>
     *
     * <p>成功之后旧的令牌依然有效(JWT 无状态,默认 2 小时)。要做到"改密码即踢下线",
     * 得在 {@link JwtService} 外面叠一层令牌黑名单,目前没做,README §8 里有记录。</p>
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody(required = false) ChangePasswordRequest request) {
        if (request == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        userService.changePassword(request.getUsername(), request.getOldPassword(), request.getNewPassword());
        return ApiResponse.ok();
    }

    /**
     * 登出:JWT 无状态,这里只清理服务端上下文,客户端丢弃令牌即可。
     * 如需"立即失效",可在 {@link JwtService} 上叠加一个令牌黑名单。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserContext.clear();
        AuthCookie.clear(httpRequest, httpResponse);
        return ApiResponse.ok();
    }

    /** 登录成功后前端要跳转的地址。留空则用本服务默认的问答页 /faq.html。 */
    private String resolveRedirectUrl() {
        String configured = properties.auth().redirectUrl();
        if (configured == null || configured.isBlank()) {
            return "/faq.html";
        }
        return configured.trim();
    }
}
