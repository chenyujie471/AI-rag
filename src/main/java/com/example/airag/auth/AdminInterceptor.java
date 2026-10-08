package com.example.airag.auth;

import java.io.IOException;

import com.example.airag.common.ApiResponse;
import com.example.airag.common.BizException;
import com.example.airag.common.JsonResponseWriter;
import com.example.airag.user.User;
import com.example.airag.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理后台鉴权:必须排在 {@link AuthInterceptor} <b>之后</b>执行(order 更大),
 * 因为它依赖后者已经写入 {@link UserContext} 的登录身份。
 *
 * <p><b>为什么每次都回查数据库,而不是把角色塞进 JWT:</b> 令牌是自包含的、签发后无法撤回。
 * 如果角色写在令牌里,管理员刚把某人的权限撤掉,那个人凭着手上的旧令牌还能继续进管理后台
 * 直到令牌过期 —— 在管理后台这种场合这不能接受。回查一次主键的代价可以忽略。</p>
 *
 * <p>顺带挡住"账号被禁用之后仍然能访问"的情况:禁用是在 {@code sys_user.status} 上,
 * 而登录态校验只看令牌,不看账号当前是否被禁用。管理接口这里多做一次检查。</p>
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

    private final UserRepository userRepository;
    private final JsonResponseWriter responseWriter;

    public AdminInterceptor(UserRepository userRepository, JsonResponseWriter responseWriter) {
        this.userRepository = userRepository;
        this.responseWriter = responseWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            // 正常情况下 AuthInterceptor 已经拦掉了,这里只是兜底
            reject(response, HttpStatus.UNAUTHORIZED,
                    BizException.unauthorized("登录状态已失效,请重新登录"));
            return false;
        }

        User user = userRepository.findById(loginUser.id()).orElse(null);
        if (user == null) {
            reject(response, HttpStatus.UNAUTHORIZED,
                    BizException.unauthorized("账号不存在或已被删除"));
            return false;
        }
        if (user.getStatus() != 1) {
            reject(response, HttpStatus.FORBIDDEN,
                    BizException.forbidden("账号已被禁用,请联系管理员"));
            return false;
        }
        if (!user.isAdmin()) {
            log.warn("非管理员访问管理接口被拒:{} [{} {}]",
                    loginUser.username(), request.getMethod(), request.getRequestURI());
            reject(response, HttpStatus.FORBIDDEN,
                    BizException.adminOnly("需要管理员权限"));
            return false;
        }
        return true;
    }

    private void reject(HttpServletResponse response, HttpStatus status, BizException ex) throws IOException {
        responseWriter.write(response, status, ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }
}
