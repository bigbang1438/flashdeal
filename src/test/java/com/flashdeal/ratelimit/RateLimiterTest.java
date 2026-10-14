package com.flashdeal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 限流算法测试：令牌桶与滑动窗口的「不误放行」是核心断言。
 *
 * <p>限流器最容易出的 bug 是并发下计数丢失导致放行数超过配额，
 * 因此每个算法都跑一遍高并发争抢，断言「放行数不超过配额」。</p>
 */
class RateLimiterTest {

    @Test
    @DisplayName("令牌桶：单线程连续请求，放行数恰好等于桶容量（初始满桶）")
    void tokenBucketAllowsExactlyCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("t", 100, 1);
        int allowed = 0;
        for (int i = 0; i < 1000; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                allowed++;
            }
        }
        System.out.printf("""
                ================ 令牌桶：容量限制 ================
                容量=100  补充速率=1/秒  连续请求=1000 次
                放行=%d  拒绝=%d
                不变量校验: 放行数 <= 容量 ? %s
                ==================================================
                """, allowed, 1000 - allowed, allowed <= 100);

        assertThat(allowed).as("紧邻的 1000 次请求最多放行 capacity 次").isBetween(100, 101);
        assertThat(limiter.rejectedCount()).isEqualTo(1000 - allowed);
    }

    @Test
    @DisplayName("令牌桶：空闲后按速率补充，突发能力被正确限制")
    void tokenBucketRefills() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("t", 10, 100);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("k").allowed()).as("桶空后立即请求应被拒绝").isFalse();

        Thread.sleep(150); // 150ms × 100/秒 ≈ 15 个令牌，但桶容量只有 10
        int burst = 0;
        for (int i = 0; i < 30; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                burst++;
            }
        }
        System.out.printf("""
                ================ 令牌桶：补充与突发 ================
                容量=10  速率=100/秒  空桶后等待=150ms
                补充后连发 30 次，放行=%d
                不变量校验: 放行数不超过桶容量 10 ? %s
                ===================================================
                """, burst, burst <= 10);

        assertThat(burst).as("补充后最多再放行 capacity 个（突发上限）").isBetween(9, 10);
    }

    @Test
    @DisplayName("令牌桶：32 线程并发抢 500 个令牌，放行数不超过配额")
    void tokenBucketConcurrentDoesNotOverAllow() throws InterruptedException {
        int capacity = 500;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("t", capacity, 1);
        int threads = 32;
        int perThread = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder allowed = new LongAdder();
        LongAdder rejected = new LongAdder();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("shared").allowed()) {
                            allowed.increment();
                        } else {
                            rejected.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - begin;
        pool.shutdownNow();

        long total = (long) threads * perThread;
        System.out.printf("""
                ================ 令牌桶：并发不超发 ================
                线程=%d  每线程请求=%d  总请求=%d
                桶容量=%d  补充速率=1/秒（可忽略）
                放行=%d  拒绝=%d  耗时=%dms
                不变量校验: 放行数 <= 容量+补充 ? %s
                不变量校验: 放行+拒绝 == 总请求 ? %s
                ===================================================
                """, threads, perThread, total, capacity, allowed.sum(), rejected.sum(),
                elapsedNanos / 1_000_000,
                allowed.sum() <= capacity + elapsedNanos / 1_000_000_000.0,
                allowed.sum() + rejected.sum() == total);

        assertThat(allowed.sum()).as("并发下绝不能超过配额").isLessThanOrEqualTo(capacity + 1);
        assertThat(allowed.sum() + rejected.sum()).isEqualTo(total);
    }

    @Test
    @DisplayName("滑动窗口：100 配额 / 1 秒，连续 1000 次请求放行不超过 100")
    void slidingWindowLimitsExactly() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter("w", 100, 1000);
        int allowed = 0;
        for (int i = 0; i < 1000; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                allowed++;
            }
        }
        System.out.printf("""
                ================ 滑动窗口：配额限制 ================
                配额=100 / 窗口=1000ms  连续请求=1000 次
                放行=%d  拒绝=%d
                不变量校验: 放行数 <= 配额 ? %s
                ===================================================
                """, allowed, 1000 - allowed, allowed <= 100);

        assertThat(allowed).as("同一窗口内放行数不得超过配额").isLessThanOrEqualTo(100);
        assertThat(allowed).as("配额应该被用满").isEqualTo(100);
    }

    @Test
    @DisplayName("滑动窗口：窗口滑过后配额恢复")
    void slidingWindowRecovers() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter("w", 50, 500);
        int first = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                first++;
            }
        }
        Thread.sleep(650);
        int second = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                second++;
            }
        }
        System.out.printf("""
                ================ 滑动窗口：窗口滑动恢复 ================
                配额=50 / 窗口=500ms
                第一轮放行=%d  等待 650ms 后第二轮放行=%d
                不变量校验: 第一轮 <= 50 ? %s
                不变量校验: 窗口滑过后配额恢复（第二轮 > 0）? %s
                =======================================================
                """, first, second, first <= 50, second > 0);

        assertThat(first).isLessThanOrEqualTo(50);
        assertThat(second).as("窗口滑过后应有新配额").isPositive();
    }

    @Test
    @DisplayName("滑动窗口：32 线程并发抢 300 配额，放行数不超过配额")
    void slidingWindowConcurrentDoesNotOverAllow() throws InterruptedException {
        int limit = 300;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter("w", limit, 60_000);
        int threads = 32;
        int perThread = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder allowed = new LongAdder();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("shared").allowed()) {
                            allowed.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        long total = (long) threads * perThread;
        System.out.printf("""
                ================ 滑动窗口：并发不超发 ================
                线程=%d  总请求=%d  配额=%d  窗口=60s（测试期内不滑动）
                放行=%d  拒绝=%d
                不变量校验: 放行数 <= 配额 ? %s
                =====================================================
                """, threads, total, limit, allowed.sum(), total - allowed.sum(), allowed.sum() <= limit);

        assertThat(allowed.sum()).as("并发下放行数绝不能超过配额").isLessThanOrEqualTo(limit);
        assertThat(allowed.sum()).as("配额应被用满（不能过度拒绝）").isEqualTo(limit);
    }

    @Test
    @DisplayName("按 key 隔离：不同用户的配额互不影响")
    void perKeyIsolation() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("user", 10, 1);
        int user1Allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.tryAcquire("user:1").allowed()) {
                user1Allowed++;
            }
        }
        int user2Allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.tryAcquire("user:2").allowed()) {
                user2Allowed++;
            }
        }
        System.out.printf("""
                ================ 限流 key 隔离 ================
                配额=10/用户
                user:1 放行=%d   user:2 放行=%d   跟踪 key 数=%d
                不变量校验: 两个用户各自独立配额 ? %s
                ==============================================
                """, user1Allowed, user2Allowed, limiter.trackedKeys(),
                user1Allowed == 10 && user2Allowed == 10);

        assertThat(user1Allowed).isEqualTo(10);
        assertThat(user2Allowed).as("user:2 不应受 user:1 影响").isEqualTo(10);
        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }

    @Test
    @DisplayName("限流结果携带可用的 Retry-After 建议")
    void rejectResultCarriesRetryHint() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("t", 1, 10);
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        RateLimitResult rejected = limiter.tryAcquire("k");

        System.out.printf("""
                ================ 限流拒绝响应 ================
                算法=%s  配额=1  速率=10/秒
                放行=%s  拒绝原因维度=%s  建议重试=%dms
                不变量校验: 拒绝时给出正的重试时间 ? %s
                ==============================================
                """, limiter.algorithmName(), rejected.allowed(), rejected.dimension(),
                rejected.retryAfterMillis(), rejected.retryAfterMillis() > 0);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMillis()).isPositive();
        assertThat(rejected.remaining()).isZero();
    }
}
