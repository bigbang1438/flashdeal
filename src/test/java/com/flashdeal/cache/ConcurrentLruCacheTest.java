package com.flashdeal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 本地缓存单元测试：TTL、LRU、命中率统计、并发正确性。
 *
 * <p>不启动 Spring 容器，直接构造 {@link ConcurrentLruCache}，
 * 因此这些用例跑得很快（毫秒级），可以放心覆盖边界。</p>
 */
class ConcurrentLruCacheTest {

    @Test
    @DisplayName("基本读写：未命中返回 null，命中后统计正确")
    void basicGetPutAndStats() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("test", 100, 60_000);

        assertThat(cache.get("missing")).isNull();
        cache.put("a", "1", 60_000);
        assertThat(cache.get("a")).isEqualTo("1");

        CacheStats stats = cache.stats();
        System.out.printf("""
                ================ L1 缓存基本统计 ================
                命中=%d  未命中=%d  命中率=%.4f  条目数=%d  容量=%d
                ================================================
                """, stats.hitCount(), stats.missCount(), stats.hitRate(), stats.size(), stats.capacity());

        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(0.5);
        assertThat(cache.name()).isEqualTo("test");
    }

    @Test
    @DisplayName("TTL 过期：过期后按未命中处理并计入 expireCount")
    void ttlExpiry() throws InterruptedException {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("ttl", 100, 50);
        cache.put("k", "v", 50);
        assertThat(cache.get("k")).isEqualTo("v");

        Thread.sleep(80);
        assertThat(cache.get("k")).as("过期后必须返回 null").isNull();

        CacheStats stats = cache.stats();
        System.out.printf("""
                ================ L1 TTL 过期统计 ================
                TTL=50ms  等待=80ms
                命中=%d  未命中=%d  过期数=%d  条目数=%d
                ================================================
                """, stats.hitCount(), stats.missCount(), stats.expireCount(), stats.size());

        assertThat(stats.expireCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("LRU 淘汰：超出容量后淘汰最久未使用，且淘汰数可统计")
    void lruEviction() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("lru", 3, 60_000);
        cache.put("a", "1", 60_000);
        cache.put("b", "2", 60_000);
        cache.put("c", "3", 60_000);
        // 访问 a，让 b 成为最久未使用
        assertThat(cache.get("a")).isEqualTo("1");
        cache.put("d", "4", 60_000);

        CacheStats stats = cache.stats();
        System.out.printf("""
                ================ L1 LRU 淘汰 ================
                容量=3  写入=a,b,c,d  访问=a（b 成为最久未使用）
                条目数=%d  淘汰数=%d  b 是否被淘汰=%s
                ============================================
                """, stats.size(), stats.evictionCount(), cache.get("b") == null);

        assertThat(cache.size()).isEqualTo(3);
        assertThat(stats.evictionCount()).isEqualTo(1);
        assertThat(cache.get("b")).as("最久未使用的 b 应被淘汰").isNull();
        assertThat(cache.get("a")).isNotNull();
        assertThat(cache.get("d")).isNotNull();
    }

    @Test
    @DisplayName("purgeExpired：后台清理能批量清掉过期条目")
    void purgeExpired() throws InterruptedException {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("purge", 100, 30);
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, "v" + i, 30);
        }
        assertThat(cache.size()).isEqualTo(50);
        Thread.sleep(60);
        int purged = cache.purgeExpired();

        System.out.printf("""
                ================ L1 过期批量清理 ================
                写入=50  TTL=30ms  等待=60ms
                清理=%d  剩余条目=%d  过期计数=%d
                ================================================
                """, purged, cache.size(), cache.stats().expireCount());

        assertThat(purged).isEqualTo(50);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("并发读写：32 线程 × 5000 次访问下统计自洽且无异常")
    void concurrentAccessIsConsistent() throws InterruptedException {
        int threads = 32;
        int opsPerThread = 5000;
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("concurrent", 1000, 60_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder hit = new LongAdder();
        LongAdder miss = new LongAdder();
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "k" + ((tid * 7 + i) % 500);
                        String v = cache.get(key);
                        if (v == null) {
                            miss.increment();
                            cache.put(key, "v" + i, 60_000);
                        } else {
                            hit.increment();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();

        CacheStats stats = cache.stats();
        long totalOps = (long) threads * opsPerThread;

        System.out.printf("""
                ================ L1 并发访问一致性 ================
                线程=%d  每线程操作=%d  总操作=%d  完成=%s  异常=%d
                缓存命中=%d  缓存未命中=%d  统计总和=%d
                独立计数命中=%d  独立计数未命中=%d  独立总和=%d
                条目数=%d  淘汰数=%d
                不变量校验: 缓存统计总和 == 总操作数 ? %s
                不变量校验: 独立计数与缓存统计一致 ? %s
                ==================================================
                """, threads, opsPerThread, totalOps, finished, errors.get(),
                stats.hitCount(), stats.missCount(), stats.hitCount() + stats.missCount(),
                hit.sum(), miss.sum(), hit.sum() + miss.sum(),
                stats.size(), stats.evictionCount(),
                (stats.hitCount() + stats.missCount()) == totalOps,
                stats.hitCount() == hit.sum() && stats.missCount() == miss.sum());

        assertThat(finished).isTrue();
        assertThat(errors.get()).as("并发访问不应抛异常").isZero();
        assertThat(stats.hitCount() + stats.missCount()).as("每次 get 都应被计入统计").isEqualTo(totalOps);
        assertThat(stats.hitCount()).isEqualTo(hit.sum());
        assertThat(stats.missCount()).isEqualTo(miss.sum());
        assertThat(cache.size()).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("getOrLoad：未命中时回源且只回源一次，之后走缓存")
    void getOrLoadLoadsOnce() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("loader", 100, 60_000);
        AtomicLong loadCount = new AtomicLong();

        for (int i = 0; i < 100; i++) {
            String v = cache.getOrLoad("hot", k -> {
                loadCount.incrementAndGet();
                return "loaded";
            }, 60_000);
            assertThat(v).isEqualTo("loaded");
        }

        System.out.printf("""
                ================ L1 getOrLoad ================
                访问次数=100  回源次数=%d  命中=%d
                不变量校验: 回源次数 == 1 ? %s
                ==============================================
                """, loadCount.get(), cache.stats().hitCount(), loadCount.get() == 1);

        assertThat(loadCount.get()).as("热点 key 只应回源一次").isEqualTo(1);
        assertThat(cache.stats().hitCount()).isEqualTo(99);
    }

    @Test
    @DisplayName("invalidate：主动失效后立即不可见")
    void invalidateWorks() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>("inv", 100, 60_000);
        cache.put("x", "1", 60_000);
        assertThat(cache.get("x")).isEqualTo("1");
        cache.invalidate("x");
        assertThat(cache.get("x")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("多线程争抢同一批 key 时不会出现重复回源导致的脏读")
    void noStaleReadUnderContention() throws InterruptedException {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>("stale", 50, 60_000);
        ConcurrentHashMap<String, Integer> source = new ConcurrentHashMap<>();
        for (int i = 0; i < 50; i++) {
            source.put("k" + i, i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(16);
        AtomicInteger mismatch = new AtomicInteger();
        for (int t = 0; t < 16; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 2000; i++) {
                        String key = "k" + (i % 50);
                        Integer v = cache.getOrLoad(key, source::get, 60_000);
                        if (!source.get(key).equals(v)) {
                            mismatch.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf("""
                ================ L1 并发脏读校验 ================
                线程=16  每线程=2000 次  不同 key=50
                读到的值与源不一致次数=%d
                不变量校验: 不一致次数 == 0 ? %s
                ================================================
                """, mismatch.get(), mismatch.get() == 0);

        assertThat(mismatch.get()).isZero();
    }
}
