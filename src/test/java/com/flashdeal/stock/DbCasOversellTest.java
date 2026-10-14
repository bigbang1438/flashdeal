package com.flashdeal.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.repository.SeckillOrderRepository;
import com.flashdeal.support.BaseIntegrationTest;

/**
 * 【核心测试】并发不超卖验证 —— 数据库乐观锁 CAS 方案。
 *
 * <h2>测试设计</h2>
 * <p>1000 个线程同时抢 100 件库存，每个线程一个独立用户（模拟真实秒杀），
 * 每个用户只抢 1 件。期望：</p>
 * <ul>
 *   <li>成功下单恰好 100 单（不超卖：不会出现 101 单）；</li>
 *   <li>也不「少卖」：库存为 0 时不应该还有剩余；</li>
 *   <li>数据库剩余库存 == 0；</li>
 *   <li>库存流水总和 + 剩余库存 == 总库存（可审计）；</li>
 * </ul>
 *
 * <p><b>为什么这是真并发而不是伪并发</b>：所有线程用同一个
 * {@link CountDownLatch} 作为起跑线，确保它们尽可能同时冲击同一行库存。</p>
 */
@TestPropertySource(properties = {
        "flashdeal.stock.mode=db-cas",
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000"
})
class DbCasOversellTest extends BaseIntegrationTest {

    private static final int THREADS = 1000;
    private static final int STOCK = 100;

    @Autowired
    private StockService stockService;

    @Autowired
    private SeckillOrderRepository orderRepo;

    private long activityId;
    private long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        productId = createProduct("CAS 并发测试商品", 19900L);
        activityId = createRunningActivity(productId, STOCK);
    }

    @Test
    @DisplayName("方案A-DB乐观锁：1000 线程抢 100 库存，恰好成功 100 单且不超卖")
    void concurrentDeductWithDbCas_shouldNotOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);

        LongAdder success = new LongAdder();
        LongAdder stockEmpty = new LongAdder();
        LongAdder versionConflict = new LongAdder();
        LongAdder other = new LongAdder();

        for (int i = 0; i < THREADS; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    startGate.await();
                    DeductResult result = stockService.deduct(activityId, userId, 1, "TEST-" + userId);
                    if (result.success()) {
                        success.increment();
                        // 扣减成功后立刻创建订单，模拟真实下单链路
                        orderRepo.insert(new SeckillOrder(null, "TEST" + userId, userId, activityId, productId,
                                1, 9900L, 0, "k" + userId, "DB_CAS",
                                java.time.LocalDateTime.now(), null,
                                java.time.LocalDateTime.now().plusMinutes(5), java.time.LocalDateTime.now()));
                    } else if (DeductResult.STOCK_NOT_ENOUGH.equals(result.reason())) {
                        stockEmpty.increment();
                    } else if (DeductResult.VERSION_CONFLICT.equals(result.reason())) {
                        versionConflict.increment();
                    } else {
                        other.increment();
                    }
                } catch (Exception e) {
                    other.increment();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        long begin = System.currentTimeMillis();
        startGate.countDown();
        boolean finished = finishGate.await(180, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdownNow();

        int dbStock = activityRepository.currentStock(activityId);
        long orderCount = orderRepo.count(activityId, null);
        long deductLogSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "DEDUCT_CAS");

        System.out.printf("""
                ================ [方案A] DB 乐观锁 CAS 并发扣减结果 ================
                线程数=%d  初始库存=%d  耗时=%dms  完成=%s
                扣减成功=%d   库存不足=%d   版本冲突=%d   其它异常=%d
                数据库剩余库存=%d
                订单数=%d
                库存流水扣减总量=%d
                不变量校验: 成功数+库存不足+冲突+异常 == 线程数 ? %s
                不变量校验: 成功数 == 初始库存(不少卖) ? %s
                不变量校验: 成功数 == 订单数 ? %s
                不变量校验: 流水扣减总量 == -成功数 ? %s
                ====================================================================
                """,
                THREADS, STOCK, elapsed, finished,
                success.sum(), stockEmpty.sum(), versionConflict.sum(), other.sum(),
                dbStock, orderCount, deductLogSum,
                (success.sum() + stockEmpty.sum() + versionConflict.sum() + other.sum()) == THREADS,
                success.sum() == STOCK,
                success.sum() == orderCount,
                deductLogSum == -success.sum());

        assertThat(finished).as("所有线程应在超时前完成").isTrue();
        assertThat(success.sum()).as("成功扣减数必须恰好等于库存（不多不少）").isEqualTo(STOCK);
        assertThat(dbStock).as("数据库剩余库存必须为 0").isZero();
        assertThat(orderCount).as("订单数必须等于成功扣减数").isEqualTo(STOCK);
        assertThat(deductLogSum).as("库存流水扣减总量必须等于 -成功数").isEqualTo(-STOCK);
        assertThat(success.sum() + stockEmpty.sum() + versionConflict.sum() + other.sum())
                .as("每个线程都必须有明确结果").isEqualTo(THREADS);
        assertThat(other.sum()).as("不应出现未预期的异常").isZero();
    }

    @Test
    @DisplayName("方案A-DB乐观锁：库存充足时所有请求都成功（不少卖）")
    void concurrentDeductWithEnoughStock_allSucceed() throws InterruptedException {
        cleanDatabase();
        productId = createProduct("充足库存商品", 19900L);
        activityId = createRunningActivity(productId, 200);

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(100);
        LongAdder success = new LongAdder();

        for (int i = 0; i < 100; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (stockService.deduct(activityId, userId, 1, "T" + userId).success()) {
                        success.increment();
                    }
                } catch (Exception ignored) {
                    // 计入失败
                } finally {
                    finishGate.countDown();
                }
            });
        }
        startGate.countDown();
        finishGate.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf("""
                ================ [方案A] 库存充足场景 ================
                并发线程=100  初始库存=200
                扣减成功=%d  剩余库存=%d
                不变量校验: 成功数 == 100 ? %s
                ====================================================
                """, success.sum(), activityRepository.currentStock(activityId), success.sum() == 100);

        assertThat(success.sum()).as("库存充足时不应有失败").isEqualTo(100);
        assertThat(activityRepository.currentStock(activityId)).isEqualTo(100);
    }

    @Test
    @DisplayName("方案A-DB乐观锁：回补库存后总量守恒")
    void rollbackKeepsTotalStockInvariant() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        LongAdder success = new LongAdder();

        for (int i = 0; i < 50; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    if (stockService.deduct(activityId, userId, 1, "R" + userId).success()) {
                        success.increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        int afterDeduct = activityRepository.currentStock(activityId);
        // 回补 10 件
        int rolledBack = 0;
        for (int i = 0; i < 10; i++) {
            if (stockService.rollback(activityId, 0L, 1, "RB" + i, "ROLLBACK_TIMEOUT")) {
                rolledBack++;
            }
        }
        int afterRollback = activityRepository.currentStock(activityId);
        long deductSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "DEDUCT_CAS");
        long rollbackSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "ROLLBACK_TIMEOUT");

        System.out.printf("""
                ================ [方案A] 库存回补守恒校验 ================
                并发扣减成功=%d  回补成功=%d
                扣减后库存=%d  回补后库存=%d
                流水: 扣减总量=%d（负）  回补总量=%d（正）  净变动=%d
                不变量校验: 剩余库存 - 扣减总量 - 回补总量 == 总库存 ? %s
                不变量校验: 剩余库存 == 总库存 + 净变动 ? %s
                ==========================================================
                """, success.sum(), rolledBack, afterDeduct, afterRollback,
                deductSum, rollbackSum, deductSum + rollbackSum,
                (afterRollback - deductSum - rollbackSum) == STOCK,
                afterRollback == STOCK + deductSum + rollbackSum);

        assertThat(rolledBack).isEqualTo(10);
        assertThat(afterRollback).isEqualTo(afterDeduct + 10);
        assertThat(afterRollback - deductSum - rollbackSum)
                .as("库存守恒：剩余库存 - 扣减总量(负) - 回补总量(正) == 总库存").isEqualTo(STOCK);
        assertThat(afterRollback).as("剩余库存 == 总库存 + 流水净变动").isEqualTo(STOCK + deductSum + rollbackSum);
    }
}
