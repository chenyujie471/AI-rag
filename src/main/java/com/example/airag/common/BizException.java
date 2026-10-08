package com.example.airag.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常:携带业务错误码与对应的 HTTP 状态码,由 {@link GlobalExceptionHandler} 统一转成 JSON。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BizException(int code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BizException(int code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.value(), message);
    }

    public static BizException tokenInvalid(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.value(), message);
    }

    public static BizException tokenExpired(String message) {
        return new BizException(ErrorCode.TOKEN_EXPIRED, HttpStatus.UNAUTHORIZED.value(), message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN.value(), message);
    }

    public static BizException quotaExhausted(String message) {
        return new BizException(ErrorCode.QUOTA_EXHAUSTED, HttpStatus.FORBIDDEN.value(), message);
    }

    public static BizException dailyLimitExceeded(String message) {
        return new BizException(ErrorCode.DAILY_LIMIT_EXCEEDED, HttpStatus.FORBIDDEN.value(), message);
    }

    /** 不是管理员却访问了管理接口。单独一个错误码,前端才能据此把页面弹回问答页而不是报错。 */
    public static BizException adminOnly(String message) {
        return new BizException(ErrorCode.ADMIN_ONLY, HttpStatus.FORBIDDEN.value(), message);
    }

    public static BizException upstream(String message) {
        return new BizException(ErrorCode.UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY.value(), message);
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
