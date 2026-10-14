package com.flashdeal.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 自研令牌桶限流器。
 *
 * <h2>算法</h2>
 * <p>桶容量 {@code capacity}，按 {@code refillPerSecond} 的速率匀速补充令牌，
 * 请求到来时若桶中有令牌则取走一个并放行，否则拒绝并给出建议重试时间。</p>
 *
 * <h2>为什么这样实现（而不是定时任务补令牌）</h2>
 * <p>用一个后台线程给每个 key 定时加令牌，key 一多就是 O(keys) 的定时器开销。
 * 这里采用「惰性补充」：每次请求时按 {@code 距上次补充的纳秒数 × 速率} 计算应补数量，
 * 单 key 的读改写用 CAS 保证原子性，无锁、无后台线程。</p>
 *
 * <h2>突发能力</h2>
 * <p>桶里最多攒下 capacity 个令牌，因此允许「空闲一段时间后瞬间放行 capacity 个请求」，
 * 这是令牌桶与滑动窗口最本质的区别（也是秒杀开场瞬间更友好的选择）。</p>
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final String name;
    private final long capacity;
    private final long refillPerSecond;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder allowedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public TokenBucketRateLimiter(String name, long capacity, long refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity 与 refillPerSecond 必须为正数");
        }
        this.name = name;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        long now = System.nanoTime();
        while (true) {
            long tokens = bucket.tokens.get();
            long lastRefill = bucket.lastRefillNanos.get();
            long elapsed = Math.max(0, now - lastRefill);
            // 惰性补充：elapsed 纳秒按速率折算成令牌（整数运算，避免浮点误差累积）
            long refilled = tokens + elapsed * refillPerSecond / 1_000_000_000L;
            long newTokens = Math.min(capacity, refilled);
            if (newTokens <= 0) {
                long needNanos = (1_000_000_000L - elapsed * refillPerSecond % 1_000_000_000L) / refillPerSecond;
                long retryMillis = Math.max(1, (needNanos + 999_999L) / 1_000_000L);
                rejectedCount.increment();
                return RateLimitResult.reject(capacity, retryMillis, name);
            }
            if (bucket.tokens.compareAndSet(tokens, newTokens - 1)) {
                // 只有真的补了令牌才推进时间戳，避免 CAS 失败时时间基准漂移
                if (newTokens > tokens) {
                    bucket.lastRefillNanos.compareAndSet(lastRefill, now);
                }
                allowedCount.increment();
                return RateLimitResult.allow(capacity, newTokens - 1, name);
            }
        }
    }

    @Override
    public String algorithmName() {
        return "token-bucket";
    }

    @Override
    public long allowedCount() {
        return allowedCount.sum();
    }

    @Override
    public long rejectedCount() {
        return rejectedCount.sum();
    }

    /** 当前跟踪的 key 数量（防止 key 无限增长，生产应加 LRU 上限）。 */
    public int trackedKeys() {
        return buckets.size();
    }

    public long capacity() {
        return capacity;
    }

    public long refillPerSecond() {
        return refillPerSecond;
    }

    /** 单 key 的令牌桶状态。 */
    private static final class Bucket {
        final AtomicLong tokens;
        final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());

        Bucket(long initialTokens) {
            this.tokens = new AtomicLong(initialTokens);
        }
    }
}
