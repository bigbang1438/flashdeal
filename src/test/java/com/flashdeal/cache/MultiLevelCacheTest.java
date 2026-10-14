package com.flashdeal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashdeal.service.CacheFacade;

/**
 * 多级缓存（L1 + L2）行为测试：回源路径、命中率口径、穿透防护、并发一致性。
 *
 * <p>直接用 {@link EmbeddedL2Cache} 构造 {@link com.flashdeal.service.CacheFacade}，
 * 不启动 Spring 容器，测试跑得快且能精确控制统计口径。</p>
 */
class MultiLevelCacheTest {

    private CacheFacade facade(int maxSize, long ttlMillis) {
        return new CacheFacade(new EmbeddedL2Cache(), maxSize, ttlMillis, "test:");
    }

    @Test
    @DisplayName("三级回源：L1 未命中 -> L2 未命中 -> DB，回源后 L1/L2 都有数据")
    void threeLevelLookup() {
        CacheFacade facade = facade(100, 60_000);
        LongAdder dbLoads = new LongAdder();

        // 第一次：全部未命中，回源
        String first = facade.get("k1", String.class, () -> {
            dbLoads.increment();
            return "db-value";
        }, Duration.ofSeconds(60));

        // 第二次：L1 命中
        String second = facade.get("k1", String.class, () -> {
            dbLoads.increment();
            return "db-value";
        }, Duration.ofSeconds(60));

        // 清空 L1，让下一次只能命中 L2
        facade.l1().clear();
        String third = facade.get("k1", String.class, () -> {
            dbLoads.increment();
            return "db-value";
        }, Duration.ofSeconds(60));

        CacheStats l1 = facade.l1Stats();
        L2Stats l2 = facade.l2Stats();

        System.out.printf("""
                ================ 三级缓存回源路径 ================
                第 1 次读取: 值=%s  （L1 未命中 -> L2 未命中 -> 回源 DB）
                第 2 次读取: 值=%s  （L1 命中）
                清空 L1 后第 3 次: 值=%s  （L1 未命中 -> L2 命中）
                DB 真实回源次数=%d（期望 1）
                L1: 命中=%d 未命中=%d 命中率=%.4f
                L2: 命中=%d 未命中=%d 命中率=%.4f  后端=%s
                综合命中率=%.4f
                不变量校验: DB 只被访问 1 次 ? %s
                不变量校验: L2 命中 1 次 ? %s
                ==================================================
                """, first, second, third, dbLoads.sum(),
                l1.hitCount(), l1.missCount(), l1.hitRate(),
                l2.hitCount(), l2.missCount(), l2.hitRate(), l2.backend(),
                facade.overallHitRate(), dbLoads.sum() == 1, l2.hitCount() == 1);

        assertThat(first).isEqualTo("db-value");
        assertThat(second).isEqualTo("db-value");
        assertThat(third).isEqualTo("db-value");
        assertThat(dbLoads.sum()).as("三级缓存下 DB 只应被访问一次").isEqualTo(1);
        assertThat(l1.hitCount()).isEqualTo(1);
        assertThat(l1.missCount()).isEqualTo(2);
        assertThat(l2.hitCount()).isEqualTo(1);
        assertThat(facade.overallHitRate()).isEqualTo(0.6667);
    }

    @Test
    @DisplayName("命中率口径：热点 90% / 冷 10% 负载下，综合命中率显著高于纯 L1")
    void hitRateWithRealisticWorkload() {
        CacheFacade facade = facade(1000, 300_000);
        int accesses = 20_000;
        int hotKeys = 90;
        int coldKeys = 1000;
        LongAdder dbLoads = new LongAdder();

        for (int i = 0; i < accesses; i++) {
            boolean hot = (i % 10) != 0; // 90% 热点
            String key = hot ? "hot:" + (i % hotKeys) : "cold:" + (i % coldKeys);
            facade.get(key, String.class, () -> {
                dbLoads.increment();
                return "v";
            }, Duration.ofSeconds(300));
        }

        CacheStats l1 = facade.l1Stats();
        L2Stats l2 = facade.l2Stats();
        double overall = facade.overallHitRate();

        System.out.printf("""
                ================ 多级缓存命中率（真实负载） ================
                总访问=%d   热点 key=%d（90%% 流量）   冷 key=%d（10%% 流量）
                L1: 命中=%d  未命中=%d  命中率=%.4f
                L2: 命中=%d  未命中=%d  命中率=%.4f
                综合命中率=%.4f
                真实回源 DB 次数=%d（回源率=%.4f）
                L1 条目数=%d / 容量=%d   淘汰=%d
                不变量校验: 综合命中率 == (L1命中+L2命中)/总访问 ? %s
                不变量校验: 命中+未命中 == 总访问 ? %s
                不变量校验: 回源次数 == L1未命中 - L2命中 ? %s
                ============================================================
                """, accesses, hotKeys, coldKeys,
                l1.hitCount(), l1.missCount(), l1.hitRate(),
                l2.hitCount(), l2.missCount(), l2.hitRate(),
                overall, dbLoads.sum(), Math.round(dbLoads.sum() * 10000.0 / accesses) / 10000.0,
                facade.l1Size(), l1.capacity(), l1.evictionCount(),
                Math.abs(overall - (l1.hitCount() + l2.hitCount()) * 1.0 / (l1.hitCount() + l1.missCount())) < 1e-9,
                l1.hitCount() + l1.missCount() == accesses,
                dbLoads.sum() == l1.missCount() - l2.hitCount());

        assertThat(l1.hitCount() + l1.missCount()).isEqualTo(accesses);
        assertThat(overall).as("真实负载下综合命中率应显著高于 0.9").isGreaterThan(0.9);
        assertThat(dbLoads.sum()).as("真实回源次数应远小于总访问").isLessThan(accesses / 5);
        assertThat(dbLoads.sum()).isEqualTo(l1.missCount() - l2.hitCount());
    }

    @Test
    @DisplayName("缓存穿透防护：查询不存在的对象也会缓存空值，避免反复打 DB")
    void nullCachingPreventsPenetration() {
        CacheFacade facade = facade(100, 60_000);
        LongAdder dbLoads = new LongAdder();

        for (int i = 0; i < 100; i++) {
            var result = facade.getOptional("missing-key", String.class, key -> {
                dbLoads.increment();
                return java.util.Optional.empty();
            }, Duration.ofSeconds(60));
            assertThat(result).isEmpty();
        }

        System.out.printf("""
                ================ 缓存穿透防护 ================
                查询不存在的 key 100 次
                真实回源 DB 次数=%d（期望 1，其余命中空值哨兵）
                L1 命中=%d  未命中=%d
                L2 命中=%d
                不变量校验: 回源只发生 1 次 ? %s
                ==============================================
                """, dbLoads.sum(), facade.l1Stats().hitCount(), facade.l1Stats().missCount(),
                facade.l2Stats().hitCount(), dbLoads.sum() == 1);

        assertThat(dbLoads.sum()).as("空值缓存必须挡住后续请求").isEqualTo(1);
    }

    @Test
    @DisplayName("缓存失效：invalidate 后 L1 与 L2 都不可见，下次读取回源")
    void invalidateClearsBothLevels() {
        CacheFacade facade = facade(100, 60_000);
        LongAdder dbLoads = new LongAdder();

        facade.get("k", String.class, () -> {
            dbLoads.increment();
            return "v1";
        }, Duration.ofSeconds(60));
        facade.invalidate("k");
        String afterInvalidate = facade.get("k", String.class, () -> {
            dbLoads.increment();
            return "v2";
        }, Duration.ofSeconds(60));

        System.out.printf("""
                ================ 缓存失效 ================
                写入 v1 -> invalidate -> 再读
                失效后读到的值=%s
                DB 回源次数=%d（期望 2）
                L2 未命中=%d（期望 2）
                不变量校验: invalidate 同时清理 L1 与 L2 ? %s
                ==========================================
                """, afterInvalidate, dbLoads.sum(), facade.l2Stats().missCount(),
                "v2".equals(afterInvalidate) && dbLoads.sum() == 2);

        assertThat(afterInvalidate).isEqualTo("v2");
        assertThat(dbLoads.sum()).isEqualTo(2);
        assertThat(facade.l2Stats().missCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发读取：32 线程同时读同一热点 key，不产生重复回源的脏数据")
    void concurrentReadsAreConsistent() throws InterruptedException {
        CacheFacade facade = facade(1000, 300_000);
        LongAdder dbLoads = new LongAdder();
        int threads = 32;
        int perThread = 2000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder mismatches = new LongAdder();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String key = "hot:" + (i % 50);
                        String v = facade.get(key, String.class, () -> {
                            dbLoads.increment();
                            return "value-for-key";
                        }, Duration.ofSeconds(300));
                        if (!"value-for-key".equals(v)) {
                            mismatches.increment();
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
        done.await(120, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - begin;
        pool.shutdownNow();

        CacheStats l1 = facade.l1Stats();
        long total = (long) threads * perThread;

        System.out.printf("""
                ================ 多级缓存并发读取 ================
                线程=%d  每线程=%d  总访问=%d  不同 key=50
                耗时=%dms  吞吐=%,d 次/秒
                L1 命中=%d  未命中=%d  命中率=%.4f
                综合命中率=%.4f
                DB 回源次数=%d
                脏读次数=%d
                不变量校验: 无脏读 ? %s
                不变量校验: 命中+未命中 == 总访问 ? %s
                不变量校验: 回源次数 <= 不同 key 数 ? %s
                =================================================
                """, threads, perThread, total, elapsedNanos / 1_000_000,
                total * 1_000_000_000L / Math.max(1, elapsedNanos),
                l1.hitCount(), l1.missCount(), l1.hitRate(), facade.overallHitRate(),
                dbLoads.sum(), mismatches.sum(),
                mismatches.sum() == 0,
                l1.hitCount() + l1.missCount() == total,
                dbLoads.sum() <= 50 * 4);

        assertThat(mismatches.sum()).as("并发下不应读到错误数据").isZero();
        assertThat(l1.hitCount() + l1.missCount()).isEqualTo(total);
        assertThat(facade.overallHitRate()).as("热点场景命中率应 > 0.99").isGreaterThan(0.99);
    }

    @Test
    @DisplayName("L2 独立语义：TTL 过期、INCRBY 原子自增")
    void l2CacheSemantics() throws InterruptedException {
        EmbeddedL2Cache l2 = new EmbeddedL2Cache();

        l2.put("a", "1", Duration.ofMillis(50));
        String before = l2.get("a");
        Thread.sleep(90);
        String after = l2.get("a");

        long v1 = l2.increment("counter", 5);
        long v2 = l2.increment("counter", 7);

        System.out.printf("""
                ================ L2 缓存语义 ================
                TTL=50ms: 立即读=%s   90ms 后读=%s
                INCRBY: 5 -> %d    再 +7 -> %d
                统计: 命中=%d 未命中=%d 过期=%d 写入=%d
                不变量校验: TTL 到期后返回 null ? %s
                不变量校验: 自增结果正确 (5, 12) ? %s
                ============================================
                """, before, after, v1, v2,
                l2.stats().hitCount(), l2.stats().missCount(),
                l2.stats().expiredCount(), l2.stats().writeCount(),
                after == null, v1 == 5 && v2 == 12);

        assertThat(before).isEqualTo("1");
        assertThat(after).isNull();
        assertThat(v1).isEqualTo(5);
        assertThat(v2).isEqualTo(12);
    }

    @Test
    @DisplayName("L2 并发自增：多线程 INCRBY 不丢更新（模拟 Redis 单命令原子性）")
    void l2ConcurrentIncrementIsAtomic() throws InterruptedException {
        EmbeddedL2Cache l2 = new EmbeddedL2Cache();
        int threads = 16;
        int perThread = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        l2.increment("shared-counter", 1);
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

        long expected = (long) threads * perThread;
        long actual = Long.parseLong(l2.get("shared-counter"));

        System.out.printf("""
                ================ L2 并发原子自增 ================
                线程=%d  每线程 INCRBY 1 执行 %d 次
                期望值=%d  实际值=%d  丢失=%d
                不变量校验: 无丢失更新 ? %s
                ================================================
                """, threads, perThread, expected, actual, expected - actual, expected == actual);

        assertThat(actual).as("并发自增不能丢更新").isEqualTo(expected);
    }
}
