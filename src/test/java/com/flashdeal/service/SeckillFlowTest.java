package com.flashdeal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.support.BaseIntegrationTest;

/**
 * 秒杀主链路与多级缓存集成测试：验证缓存确实被主链路使用，命中率可观测。
 */
@TestPropertySource(properties = {
        "flashdeal.stock.mode=db-cas",
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000",
        "flashdeal.rate-limit.order.user-capacity=1000000",
        "flashdeal.rate-limit.order.user-refill-per-second=1000000"
})
class SeckillFlowTest extends BaseIntegrationTest {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private OrderQueueService orderQueueService;

    @Autowired
    private CacheFacade cacheFacade;

    @Test
    @DisplayName("主链路缓存：反复读活动详情，L1 命中率随访问次数上升，DB 回源只发生一次")
    void activityReadIsCached() {
        cleanDatabase();
        long productId = createProduct("缓存链路商品", 19900L);
        long activityId = createRunningActivity(productId, 100);

        var before = cacheFacade.l1Stats();
        int reads = 500;
        for (int i = 0; i < reads; i++) {
            activityService.getActivity(activityId);
        }
        var after = cacheFacade.l1Stats();

        long hits = after.hitCount() - before.hitCount();
        long misses = after.missCount() - before.missCount();

        System.out.printf("""
                ================ 主链路缓存命中率 ================
                读取活动详情 %d 次（同一 activityId）
                L1 命中=%d  L1 未命中=%d  本次命中率=%.4f
                累计 L1 命中率=%.4f
                综合命中率（含 L2）=%.4f
                L1 条目数=%d  容量=%d
                不变量校验: 500 次读取中只有 1 次未命中 ? %s
                不变量校验: 本次命中率 >= 0.99 ? %s
                ==================================================
                """, reads, hits, misses, CacheStats.rate(hits, misses),
                after.hitRate(), cacheFacade.overallHitRate(), after.size(), after.capacity(),
                misses <= 1, CacheStats.rate(hits, misses) >= 0.99);

        assertThat(misses).as("同一活动反复读取只应回源一次").isLessThanOrEqualTo(1);
        assertThat(hits).isGreaterThanOrEqualTo(reads - 1);
    }

    @Test
    @DisplayName("缓存失效：活动更新后立刻读到新数据")
    void cacheInvalidationOnWrite() {
        cleanDatabase();
        long productId = createProduct("失效测试商品", 19900L);
        long activityId = createRunningActivity(productId, 100);

        var first = activityService.getActivity(activityId);
        activityRepository.updateStatus(activityId, 3);
        activityService.evict(activityId);
        var afterEvict = activityService.getActivity(activityId);

        System.out.printf("""
                ================ 缓存失效生效 ================
                活动 status: 初始=%d  更新为 3 后 evict 再读=%d
                不变量校验: 失效后读到最新状态 ? %s
                ==============================================
                """, first.status(), afterEvict.status(), afterEvict.status() == 3);

        assertThat(afterEvict.status()).isEqualTo(3);
    }

    @Test
    @DisplayName("下单链路完整落库：库存、订单、流水三者一致")
    void orderFlowPersistsConsistently() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("一致性商品", 19900L);
        int totalStock = 50;
        long activityId = createRunningActivity(productId, totalStock);

        int users = 50;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        LongAdder success = new LongAdder();

        for (int i = 0; i < users; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    start.await();
                    seckillService.seckill(new SeckillOrderRequest(activityId, userId, 1, nextToken()));
                    success.increment();
                } catch (Exception ignored) {
                    // 计入失败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        boolean drained = orderQueueService.awaitDrained(30_000);

        int stock = activityRepository.currentStock(activityId);
        long orderCount = orderRepository.count(activityId, null);
        long deductSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "DEDUCT_CAS");
        long distinctUsers = orderRepository.findPage(activityId, null, 0, 200).stream()
                .map(o -> o.userId()).distinct().count();

        System.out.printf("""
                ================ 下单链路一致性 ================
                并发用户=%d  初始库存=%d  队列已排空=%s
                下单成功=%d
                数据库订单数=%d
                去重后用户数=%d
                剩余库存=%d
                库存流水扣减总量=%d
                不变量校验: 订单数 == 成功数 ? %s
                不变量校验: 订单数 == 初始库存（全部卖出）? %s
                不变量校验: 订单数 == 去重用户数（一人一单）? %s
                不变量校验: 剩余库存 == 0 ? %s
                不变量校验: 扣减流水 == -订单数 ? %s
                ================================================
                """, users, totalStock, drained, success.sum(), orderCount, distinctUsers,
                stock, deductSum,
                orderCount == success.sum(),
                orderCount == totalStock,
                orderCount == distinctUsers,
                stock == 0,
                deductSum == -orderCount);

        assertThat(drained).isTrue();
        assertThat(orderCount).isEqualTo(totalStock);
        assertThat(distinctUsers).as("一个用户只能有一单").isEqualTo(orderCount);
        assertThat(stock).isZero();
        assertThat(deductSum).isEqualTo(-orderCount);
    }

    @Test
    @DisplayName("队列削峰：真实下单路径下队列确实吸收了峰值")
    void queueAbsorbsBurstOnRealPath() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("削峰商品", 19900L);
        // 库存设得比并发用户多，保证所有请求都走「成功下单」路径（不被库存不足提前拦截），
        // 这样队列压力才是真实的。1000 并发对单行 CAS 已经能压出明显积压。
        int totalStock = 1000;
        long activityId = createRunningActivity(productId, totalStock);

        var before = orderQueueService.stats();
        int users = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        LongAdder success = new LongAdder();

        for (int i = 0; i < users; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    start.await();
                    seckillService.seckill(new SeckillOrderRequest(activityId, userId, 1, nextToken()));
                    success.increment();
                } catch (Exception ignored) {
                    // 计入失败
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        done.await(180, TimeUnit.SECONDS);
        long enqueueNanos = System.nanoTime() - begin;
        pool.shutdownNow();

        // 入队阶段结束时的积压（此刻消费者还在追赶）
        long backlogAtBurstEnd = orderQueueService.backlog();
        boolean drained = orderQueueService.awaitDrained(60_000);
        var after = orderQueueService.stats();

        System.out.printf("""
                ================ 真实链路削峰 ================
                瞬时灌入=%d 个下单请求（库存=%d）
                下单处理耗时=%dms（请求线程只做入队）
                下单成功=%d
                峰值积压=%d（= 被队列吸收的瞬时峰值）
                峰值时刻积压=%d
                平均批量=%.2f  平均队列延迟=%.2fms  最大队列延迟=%dms
                队列已排空=%s
                同步兜底次数=%d  队列满拒绝=%d
                数据库订单数=%d  剩余库存=%d
                不变量校验: 峰值积压 > 0（削峰生效）? %s
                不变量校验: 全部落库 ? %s
                ==============================================
                """, users, totalStock, enqueueNanos / 1_000_000, success.sum(),
                after == null ? -1 : after.peakBacklog(), backlogAtBurstEnd,
                after == null ? 0 : after.avgBatchSize(),
                after == null ? 0 : after.avgConsumeLatencyMillis(),
                after == null ? 0 : after.maxConsumeLatencyMillis(),
                drained, orderQueueService.syncFallbackCount(), orderQueueService.rejectedCount(),
                orderRepository.count(activityId, null), activityRepository.currentStock(activityId),
                after != null && after.peakBacklog() > 0,
                drained);

        assertThat(drained).isTrue();
        assertThat(after).isNotNull();
        assertThat(after.peakBacklog()).as("真实链路下队列必须吸收到峰值").isPositive();
        assertThat(orderRepository.count(activityId, null)).isEqualTo(totalStock);
        assertThat(before).isNotNull();
    }

    @Test
    @DisplayName("订单队列参数可见：配置与运行时统计一致")
    void queueConfigIsApplied() {
        var stats = orderQueueService.stats();
        System.out.printf("""
                ================ 队列配置生效 ================
                容量=%d  消费者线程=%d  批量上限=%d
                已发布=%d  已消费=%d  积压=%d
                不变量校验: 容量为 2 的幂 ? %s
                不变量校验: 消费者线程 > 0 ? %s
                ==============================================
                """, stats.capacity(), stats.consumerThreads(), 64,
                stats.publishedCount(), stats.consumedCount(), stats.backlog(),
                Integer.bitCount(stats.capacity()) == 1, stats.consumerThreads() > 0);

        assertThat(Integer.bitCount(stats.capacity())).as("环形缓冲容量必须是 2 的幂").isEqualTo(1);
        assertThat(stats.consumerThreads()).isEqualTo(4);
        assertThat(orderQueueService.running()).isTrue();
    }
}
