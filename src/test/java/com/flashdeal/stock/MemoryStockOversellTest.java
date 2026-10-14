package com.flashdeal.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.support.BaseIntegrationTest;

/**
 * 【核心测试】并发不超卖验证 —— 内存预扣 + 异步落库方案。
 *
 * <p>与 {@link DbCasOversellTest} 完全对称的设计（同样 1000 线程抢 100 件），
 * 唯一区别是库存扣减走 {@link MemoryStockService} 的 {@code AtomicLong.compareAndSet}。</p>
 *
 * <h2>额外断言：内存与数据库的最终一致</h2>
 * <p>内存方案是最终一致的，因此测试会在扣减完成后等待落库队列排空，
 * 再断言「数据库库存 == 内存库存 == 0」，把「最终一致确实会一致」这件事也测掉。</p>
 */
@TestPropertySource(properties = {
        "flashdeal.stock.mode=memory",
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000"
})
class MemoryStockOversellTest extends BaseIntegrationTest {

    private static final int THREADS = 1000;
    private static final int STOCK = 100;

    @Autowired
    private StockService stockService;

    private long activityId;
    private long productId;
    private MemoryStockService memoryStockService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        productId = createProduct("内存预扣测试商品", 19900L);
        activityId = createRunningActivity(productId, STOCK);
        assertThat(stockService).isInstanceOf(MemoryStockService.class);
        memoryStockService = (MemoryStockService) stockService;
        // 库存预热：与生产链路一致，避免把懒加载算进并发窗口
        memoryStockService.preload(activityId, STOCK);
    }

    @AfterEach
    void tearDown() {
        // 等待异步落库排空，避免影响后续测试的库内数据
        memoryStockService.awaitDrained(30_000);
    }

    @Test
    @DisplayName("方案B-内存预扣：1000 线程抢 100 库存，恰好成功 100 次且不超卖")
    void concurrentDeductWithMemory_shouldNotOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);

        LongAdder success = new LongAdder();
        LongAdder stockEmpty = new LongAdder();
        LongAdder other = new LongAdder();

        for (int i = 0; i < THREADS; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    startGate.await();
                    DeductResult result = stockService.deduct(activityId, userId, 1, "MEM-" + userId);
                    if (result.success()) {
                        success.increment();
                    } else if (DeductResult.STOCK_NOT_ENOUGH.equals(result.reason())) {
                        stockEmpty.increment();
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
        boolean finished = finishGate.await(120, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdownNow();

        int memoryStock = stockService.currentStock(activityId);
        MemoryStockStats stats = memoryStockService.stats();
        boolean drained = memoryStockService.awaitDrained(30_000);
        int dbStock = activityRepository.currentStock(activityId);

        System.out.printf("""
                ================ [方案B] 内存预扣 + 异步落库并发扣减结果 ================
                线程数=%d  初始库存=%d  耗时=%dms  完成=%s
                扣减成功=%d   库存不足=%d   其它异常=%d
                内存剩余库存=%d   数据库剩余库存=%d   队列已排空=%s
                内存扣减次数=%d   CAS 重试总数=%d   单次最大 CAS 重试=%d
                落库失败次数=%d   队列积压=%d
                不变量校验: 成功数+库存不足+异常 == 线程数 ? %s
                不变量校验: 成功数 == 初始库存(不少卖) ? %s
                不变量校验: 内存库存 == 0 ? %s
                不变量校验: 数据库库存 == 内存库存(最终一致) ? %s
                不变量校验: 落库失败次数 == 0 ? %s
                ==========================================================================
                """,
                THREADS, STOCK, elapsed, finished,
                success.sum(), stockEmpty.sum(), other.sum(),
                memoryStock, dbStock, drained,
                stats.deductCount(), stats.casRetryTotal(), stats.casRetryMax(),
                stats.persistFailCount(), stats.backlog(),
                (success.sum() + stockEmpty.sum() + other.sum()) == THREADS,
                success.sum() == STOCK,
                memoryStock == 0,
                dbStock == memoryStock,
                stats.persistFailCount() == 0);

        assertThat(finished).as("所有线程应在超时前完成").isTrue();
        assertThat(success.sum()).as("内存预扣成功数必须恰好等于库存").isEqualTo(STOCK);
        assertThat(memoryStock).as("内存库存必须为 0").isZero();
        assertThat(drained).as("异步落库队列必须排空").isTrue();
        assertThat(dbStock).as("数据库库存最终必须与内存一致").isEqualTo(memoryStock);
        assertThat(stats.persistFailCount()).as("不应有落库失败").isZero();
        assertThat(other.sum()).as("不应出现未预期异常").isZero();
    }

    @Test
    @DisplayName("方案B-内存预扣：库存充足时全部成功（不少卖）")
    void memoryDeductWithEnoughStock_allSucceed() throws InterruptedException {
        cleanDatabase();
        productId = createProduct("内存充足库存商品", 19900L);
        activityId = createRunningActivity(productId, 200);
        memoryStockService.preload(activityId, 200);

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(100);
        LongAdder success = new LongAdder();

        for (int i = 0; i < 100; i++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (stockService.deduct(activityId, userId, 1, "M" + userId).success()) {
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
        memoryStockService.awaitDrained(30_000);

        System.out.printf("""
                ================ [方案B] 库存充足场景 ================
                并发线程=100  初始库存=200
                扣减成功=%d  内存剩余=%d  数据库剩余=%d
                不变量校验: 成功数 == 100 ? %s
                ====================================================
                """, success.sum(), stockService.currentStock(activityId),
                activityRepository.currentStock(activityId), success.sum() == 100);

        assertThat(success.sum()).isEqualTo(100);
        assertThat(stockService.currentStock(activityId)).isEqualTo(100);
        assertThat(activityRepository.currentStock(activityId)).isEqualTo(100);
    }

    @Test
    @DisplayName("方案B-内存预扣：批量落库的批量大小确实大于 1（批量合并生效）")
    void batchPersistActuallyBatches() throws InterruptedException {
        cleanDatabase();
        productId = createProduct("批量落库测试商品", 19900L);
        activityId = createRunningActivity(productId, 2000);
        memoryStockService.preload(activityId, 2000);

        for (int i = 0; i < 2000; i++) {
            stockService.deduct(activityId, nextUserId(), 1, "B" + i);
        }
        boolean drained = memoryStockService.awaitDrained(30_000);
        MemoryStockStats stats = memoryStockService.stats();

        System.out.printf("""
                ================ [方案B] 批量落库效果 ================
                扣减次数=%d  队列已排空=%s
                队列发布数=%d  消费成功数=%d  平均批量=%.2f
                落库失败=%d
                不变量校验: 数据库库存 == 0 ? %s
                =====================================================
                """, stats.deductCount(), drained,
                stats.queueStats().publishedCount(), stats.queueStats().consumedCount(),
                stats.queueStats().avgBatchSize(), stats.persistFailCount(),
                activityRepository.currentStock(activityId) == 0);

        assertThat(drained).isTrue();
        assertThat(activityRepository.currentStock(activityId)).isZero();
        assertThat(stats.queueStats().avgBatchSize())
                .as("批量消费必须真实生效（平均批量 > 1）").isGreaterThan(1.0);
    }
}
