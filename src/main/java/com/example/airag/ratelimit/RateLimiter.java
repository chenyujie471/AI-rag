package com.example.airag.ratelimit;

/**
 * 限流器抽象。
 *
 * <p>当前提供 {@link InMemorySlidingWindowRateLimiter}(单机内存滑动窗口)。
 * 如果将来需要多实例共享配额,只要新增一个基于 Redis 的实现替换掉 Bean 即可,
 * 调用方(拦截器)不需要改动。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取一次请求配额。
     *
     * @param key           限流维度(例如 {@code u:1|/api/chat/**})
     * @param limit         窗口内最大请求数,0 或负数表示不限制
     * @param windowSeconds 窗口大小(秒)
     */
    RateLimitResult tryAcquire(String key, int limit, long windowSeconds);
}
