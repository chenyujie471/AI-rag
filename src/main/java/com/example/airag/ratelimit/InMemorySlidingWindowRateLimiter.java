package com.example.airag.ratelimit;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 单机版滑动窗口限流(滑动窗口日志算法)。
 *
 * <p>实现要点:</p>
 * <ul>
 *   <li>每个限流维度一个时间戳队列,{@link ConcurrentHashMap#compute} 保证同 key 的更新是串行的;</li>
 *   <li>请求到来时先剔除窗口外的旧时间戳,再判断是否超过阈值 —— 因此不会出现固定窗口的"临界突刺";</li>
 *   <li>定时清理长期不活跃的 key,避免内存持续增长。</li>
 * </ul>
 */
@Component
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemorySlidingWindowRateLimiter.class);
    /** 超过这个时长没有任何请求的 key 直接回收。 */
    private static final long STALE_THRESHOLD_MILLIS = 30L * 60L * 1000L;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryAcquire(String key, int limit, long windowSeconds) {
        long effectiveWindowSeconds = Math.max(windowSeconds, 1L);
        if (limit <= 0) {
            return new RateLimitResult(true, limit, Integer.MAX_VALUE, 0L, effectiveWindowSeconds);
        }
        long now = System.currentTimeMillis();
        long windowMillis = effectiveWindowSeconds * 1000L;

        // compute 返回的是新的队列值,结果通过长度为 1 的数组带出来
        RateLimitResult[] holder = new RateLimitResult[1];
        windows.compute(key, (k, existing) -> {
            Deque<Long> queue = existing == null ? new ConcurrentLinkedDeque<>() : existing;
            long threshold = now - windowMillis;
            while (!queue.isEmpty() && queue.peekFirst() <= threshold) {
                queue.pollFirst();
            }
            if (queue.size() < limit) {
                queue.addLast(now);
                holder[0] = new RateLimitResult(true, limit, limit - queue.size(), 0L, effectiveWindowSeconds);
            } else {
                Long oldest = queue.peekFirst();
                long waitMillis = oldest == null ? windowMillis : oldest + windowMillis - now;
                long retryAfterSeconds = Math.max(1L, (waitMillis + 999L) / 1000L);
                holder[0] = new RateLimitResult(false, limit, 0, retryAfterSeconds, effectiveWindowSeconds);
            }
            return queue;
        });
        return holder[0];
    }

    /** 定时回收空闲窗口。 */
    @Scheduled(fixedDelayString = "${app.rate-limit.cleanup-interval-ms:300000}")
    public void evictStaleWindows() {
        long staleBefore = System.currentTimeMillis() - STALE_THRESHOLD_MILLIS;
        int before = windows.size();
        windows.entrySet().removeIf(entry -> {
            Long newest = entry.getValue().peekLast();
            return newest == null || newest <= staleBefore;
        });
        int removed = before - windows.size();
        if (removed > 0) {
            log.debug("已回收空闲限流窗口 {} 个,当前剩余 {}", removed, windows.size());
        }
    }

    /** 便于监控/排查。 */
    public int trackedWindowCount() {
        return windows.size();
    }
}
