package com.example.airag.auth;

/**
 * 当前请求的登录用户(轻量,只有鉴权必需字段,避免每个请求都查库)。
 */
public record LoginUser(long id, String username) {
}
