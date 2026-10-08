package com.example.airag.auth;

import com.example.airag.common.BizException;

/**
 * 请求级登录用户上下文(ThreadLocal,由 AuthInterceptor 写入并在请求结束时清理)。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前登录用户,取不到直接抛 401。 */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw BizException.unauthorized("登录状态已失效,请重新登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
