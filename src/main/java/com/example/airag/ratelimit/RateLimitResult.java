package com.example.airag.ratelimit;

/**
 * 限流结果。
 *
 * @param allowed           是否放行
 * @param limit             本窗口允许的最大请求数
 * @param remaining         本窗口剩余可请求数
 * @param retryAfterSeconds 被拒绝时建议的重试等待秒数
 * @param windowSeconds     窗口大小(秒)
 */
public record RateLimitResult(boolean allowed, int limit, int remaining, long retryAfterSeconds, long windowSeconds) {
}
