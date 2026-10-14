package com.flashdeal.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 自研加权滑动窗口限流器。
 *
 * <h2>算法</h2>
 * <p>把窗口切成 {@code bucketCount} 个等长子桶（默认 10 个），每个子桶一个计数器。
 * 判定时对「落在当前窗口内」的所有子桶求和；跨越窗口左边界的那个子桶按重叠比例加权，
 * 因此窗口是真正随时间连续滑动的，而不是固定窗口那样在边界处放行 2 倍流量。</p>
 *
 * <pre>
 * 窗口 = [now - windowMillis, now]
 * 子桶长度 bucketLength = windowMillis / bucketCount
 * 总请求数 = Σ(完全落在窗口内的子桶) + 左边界子桶 × 重叠比例
 * </pre>
 *
 * <h2>与令牌桶的区别</h2>
 * <p>滑动窗口严格限制「任意 windowMillis 内的请求数不超过 limit」，没有突发余量；
 * 令牌桶允许攒令牌后突发。两者都实现，便于在 /metrics 里对比。</p>
 *
 * <h2>近似之处（诚实说明）</h2>
 * <p>左边界子桶的加权假设桶内请求均匀分布。桶越细误差越小；本项目默认 10 个桶，
 * 最坏情况误差不超过 1 个桶的份额。这是 Sentinel 等工业实现的通用做法。</p>
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final int DEFAULT_BUCKET_COUNT = 10;

    private final String name;
    private final long limit;
    private final long windowMillis;
    private final int bucketCount;
    private final long bucketLengthMillis;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongAdder allowedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public SlidingWindowRateLimiter(String name, long limit, long windowMillis) {
        this(name, limit, windowMillis, DEFAULT_BUCKET_COUNT);
    }

    public SlidingWindowRateLimiter(String name, long limit, long windowMillis, int bucketCount) {
        if (limit <= 0 || windowMillis <= 0 || bucketCount <= 0) {
            throw new IllegalArgumentException("limit / windowMillis / bucketCount 必须为正数");
        }
        this.name = name;
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.bucketCount = bucketCount;
        this.bucketLengthMillis = Math.max(1, windowMillis / bucketCount);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        Window window = windows.computeIfAbsent(key, k -> new Window(bucketCount));
        long now = System.currentTimeMillis();
        long currentIndex = now / bucketLengthMillis;

        // 快路径：窗口未滚动，完全无锁
        if (window.lastIndex.get() != currentIndex) {
            synchronized (window) {
                if (window.lastIndex.get() != currentIndex) {
                    rollover(window, currentIndex);
                }
            }
        }

        int slot = (int) Math.floorMod(currentIndex, bucketCount + 1);
        window.buckets.incrementAndGet(slot);

        long total = currentTotal(window, currentIndex, now);
        if (total > limit) {
            // 拒绝时回退计数，保证窗口内的计数 == 实际放行数
            window.buckets.decrementAndGet(slot);
            long wait = waitMillis(now, window, currentIndex);
            rejectedCount.increment();
            return RateLimitResult.reject(limit, wait, name);
        }
        allowedCount.increment();
        return RateLimitResult.allow(limit, limit - total, name);
    }

    /**
     * 计算当前窗口内的请求总数。
     *
     * <p>数组持有 {@code bucketCount + 1} 个子桶：下标 {@code currentIndex - bucketCount}
     * 到 {@code currentIndex}。最老的那个子桶只有一部分落在窗口内，按比例加权。</p>
     */
    private long currentTotal(Window window, long currentIndex, long now) {
        long windowStart = now - windowMillis;
        long oldestIndex = currentIndex - bucketCount;
        long oldestBucketStart = oldestIndex * bucketLengthMillis;
        long overlap = oldestBucketStart + bucketLengthMillis - windowStart;
        double weight = Math.max(0d, Math.min(1d, overlap / (double) bucketLengthMillis));

        long total = 0L;
        int oldestSlot = (int) Math.floorMod(oldestIndex, bucketCount + 1);
        total += (long) (window.buckets.get(oldestSlot) * weight);
        for (long idx = oldestIndex + 1; idx <= currentIndex; idx++) {
            total += window.buckets.get((int) Math.floorMod(idx, bucketCount + 1));
        }
        return total;
    }

    /**
     * 估算还需要等多久才有配额。
     *
     * <p>最老的那个子桶在 {@code now' - windowMillis >= oldestBucketEnd} 时完全滑出窗口，
     * 因此释放时刻为 {@code oldestBucketEnd + windowMillis}。</p>
     */
    private long waitMillis(long now, Window window, long currentIndex) {
        long oldestIndex = currentIndex - bucketCount;
        long oldestBucketEnd = (oldestIndex + 1) * bucketLengthMillis;
        long releaseAt = oldestBucketEnd + windowMillis;
        return Math.max(1, releaseAt - now);
    }

    /** 窗口滚动：清空已经滑出窗口的子桶。 */
    private void rollover(Window window, long currentIndex) {
        long lastIndex = window.lastIndex.get();
        if (currentIndex <= lastIndex) {
            return;
        }
        long from = Math.max(lastIndex + 1, currentIndex - bucketCount);
        for (long idx = from; idx <= currentIndex; idx++) {
            window.buckets.set((int) Math.floorMod(idx, bucketCount + 1), 0L);
        }
        window.lastIndex.set(currentIndex);
    }

    @Override
    public String algorithmName() {
        return "sliding-window";
    }

    @Override
    public long allowedCount() {
        return allowedCount.sum();
    }

    @Override
    public long rejectedCount() {
        return rejectedCount.sum();
    }

    public int trackedKeys() {
        return windows.size();
    }

    public long limit() {
        return limit;
    }

    public long windowMillis() {
        return windowMillis;
    }

    /** 单 key 的滑动窗口状态。 */
    private static final class Window {
        final AtomicLongArray buckets;
        final AtomicLong lastIndex = new AtomicLong(Long.MIN_VALUE);

        Window(int bucketCount) {
            // 多留一个槽位承载「跨越左边界的部分桶」
            this.buckets = new AtomicLongArray(bucketCount + 1);
        }
    }
}
