package com.example.airag.common;

/**
 * 业务错误码常量。
 */
public final class ErrorCode {

    /** 成功 */
    public static final int OK = 0;
    /** 参数错误 -> 400 */
    public static final int BAD_REQUEST = 40000;
    /** 未登录 / 令牌无效 -> 401 */
    public static final int UNAUTHORIZED = 40100;
    /** 令牌过期 -> 401 */
    public static final int TOKEN_EXPIRED = 40101;
    /** 无权限(如账号被禁用) -> 403 */
    public static final int FORBIDDEN = 40300;
    /** 额度(次数)已用完 -> 403 */
    public static final int QUOTA_EXHAUSTED = 40301;
    /** 某条知识库链路当日次数已满 -> 403 */
    public static final int DAILY_LIMIT_EXCEEDED = 40302;
    /** 当前账号不是管理员却访问了管理接口 -> 403 */
    public static final int ADMIN_ONLY = 40303;
    /** 触发频率限制 -> 429 */
    public static final int TOO_MANY_REQUESTS = 42900;
    /** 上游(FastGPT)异常 -> 502 */
    public static final int UPSTREAM_ERROR = 50200;
    /** 服务内部异常 -> 500 */
    public static final int INTERNAL_ERROR = 50000;

    private ErrorCode() {
    }
}
