package com.flashdeal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.domain.OrderStatus;
import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.dto.PayCallbackRequest;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.dto.SeckillOrderResponse;
import com.flashdeal.support.BaseIntegrationTest;

/**
 * 订单生命周期测试：支付回调幂等、超时回补库存、状态机约束。
 *
 * <p>覆盖「超时未支付回补库存」这条链路：这是防少卖的关键——
 * 库存被占住但用户不付款时，必须能把库存还回去。</p>
 */
@TestPropertySource(properties = {
        "flashdeal.stock.mode=db-cas",
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000",
        "flashdeal.rate-limit.order.user-capacity=1000000",
        "flashdeal.rate-limit.order.user-refill-per-second=1000000",
        "flashdeal.order.pay-timeout-seconds=1"
})
class OrderLifecycleTest extends BaseIntegrationTest {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private OrderQueueService orderQueueService;

    @Autowired
    private ScheduledTasks scheduledTasks;

    @Test
    @DisplayName("支付回调：重复回调幂等，不会重复发货")
    void payCallbackIsIdempotent() {
        cleanDatabase();
        long productId = createProduct("支付幂等商品", 19900L);
        long activityId = createRunningActivity(productId, 10);
        long userId = nextUserId();

        SeckillOrderResponse order = seckillService.seckill(
                new SeckillOrderRequest(activityId, userId, 1, nextToken()));
        orderQueueService.awaitDrained(10_000);

        PayCallbackRequest payRequest = new PayCallbackRequest(order.orderNo(), "MOCK", order.amountCents());
        SeckillOrderResponse first = seckillService.pay(payRequest);
        SeckillOrderResponse second = seckillService.pay(payRequest);
        SeckillOrderResponse third = seckillService.pay(payRequest);

        SeckillOrder persisted = orderRepository.findByOrderNo(order.orderNo()).orElseThrow();

        System.out.printf("""
                ================ 支付回调幂等 ================
                订单号=%s  金额=%d 分
                第 1 次回调: status=%s idempotent=%s
                第 2 次回调: status=%s idempotent=%s
                第 3 次回调: status=%s idempotent=%s
                数据库最终状态=%s  paid_at=%s
                剩余库存=%d（支付不回补库存，期望 9）
                不变量校验: 只有第 1 次是真实状态变更 ? %s
                不变量校验: 后续回调都返回幂等标记 ? %s
                ==============================================
                """, order.orderNo(), order.amountCents(),
                first.statusText(), first.idempotent(),
                second.statusText(), second.idempotent(),
                third.statusText(), third.idempotent(),
                persisted.orderStatus(), persisted.paidAt(),
                activityRepository.currentStock(activityId),
                !first.idempotent() && second.idempotent() && third.idempotent(),
                second.idempotent() && third.idempotent());

        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(third.idempotent()).isTrue();
        assertThat(persisted.status()).isEqualTo(OrderStatus.PAID.code());
        assertThat(activityRepository.currentStock(activityId)).as("支付不影响库存").isEqualTo(9);
    }

    @Test
    @DisplayName("支付金额校验：金额不一致必须拒绝")
    void payAmountMismatchIsRejected() {
        cleanDatabase();
        long productId = createProduct("金额校验商品", 19900L);
        long activityId = createRunningActivity(productId, 10);

        SeckillOrderResponse order = seckillService.seckill(
                new SeckillOrderRequest(activityId, nextUserId(), 1, nextToken()));
        orderQueueService.awaitDrained(10_000);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        seckillService.pay(new PayCallbackRequest(order.orderNo(), "MOCK", 1L)))
                .isInstanceOf(com.flashdeal.common.BizException.class)
                .satisfies(e -> {
                    com.flashdeal.common.BizException biz = (com.flashdeal.common.BizException) e;
                    System.out.printf("""
                            ================ 支付金额校验 ================
                            订单金额=%d 分   回调金额=1 分
                            抛出业务码=%d  消息=%s
                            不变量校验: 金额不符被拒绝(1001) ? %s
                            ==============================================
                            """, order.amountCents(), biz.code(), biz.getMessage(), biz.code() == 1001);
                });

        assertThat(orderRepository.findByOrderNo(order.orderNo()).orElseThrow().status())
                .as("金额不符时订单不应变成已支付").isEqualTo(OrderStatus.PENDING_PAY.code());
    }

    @Test
    @DisplayName("超时未支付：定时任务取消订单并回补库存（防少卖）")
    void expiredOrderRollsBackStock() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("超时回补商品", 19900L);
        int totalStock = 10;
        long activityId = createRunningActivity(productId, totalStock);

        // 3 个用户下单但不支付（pay-timeout-seconds=1）
        for (int i = 0; i < 3; i++) {
            seckillService.seckill(new SeckillOrderRequest(activityId, nextUserId(), 1, nextToken()));
        }
        orderQueueService.awaitDrained(10_000);
        int afterOrders = activityRepository.currentStock(activityId);

        // 等待超过支付超时时间
        Thread.sleep(1500);
        scheduledTasks.expireUnpaidOrders();
        orderQueueService.awaitDrained(5_000);

        int afterRollback = activityRepository.currentStock(activityId);
        long cancelled = orderRepository.count(activityId, OrderStatus.CANCELLED.code());
        long pending = orderRepository.count(activityId, OrderStatus.PENDING_PAY.code());
        long rollbackSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "ROLLBACK_TIMEOUT");

        System.out.printf("""
                ================ 超时未支付回补库存 ================
                初始库存=%d   3 个用户下单未支付
                下单后库存=%d（期望 7）
                等待 1.5s（支付超时=1s）后触发定时扫描
                回补后库存=%d（期望 10，全部回补）
                已取消订单数=%d   仍待支付=%d
                回补流水总量=%d（期望 +3）
                不变量校验: 库存完全回补 ? %s
                不变量校验: 订单全部取消 ? %s
                不变量校验: 回补流水 == +3 ? %s
                ===================================================
                """, totalStock, afterOrders, afterRollback, cancelled, pending, rollbackSum,
                afterRollback == totalStock, cancelled == 3, rollbackSum == 3);

        assertThat(afterOrders).isEqualTo(7);
        assertThat(afterRollback).as("超时订单必须把库存还回来").isEqualTo(totalStock);
        assertThat(cancelled).isEqualTo(3);
        assertThat(pending).isZero();
        assertThat(rollbackSum).isEqualTo(3);
    }

    @Test
    @DisplayName("超时回补的幂等性：定时任务重复执行不会重复回补库存")
    void expiredRollbackIsIdempotent() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("回补幂等商品", 19900L);
        int totalStock = 5;
        long activityId = createRunningActivity(productId, totalStock);

        seckillService.seckill(new SeckillOrderRequest(activityId, nextUserId(), 1, nextToken()));
        orderQueueService.awaitDrained(10_000);
        Thread.sleep(1500);

        // 连续执行 5 次扫描，模拟定时任务重复触发
        for (int i = 0; i < 5; i++) {
            scheduledTasks.expireUnpaidOrders();
        }
        orderQueueService.awaitDrained(5_000);

        int stock = activityRepository.currentStock(activityId);
        long rollbackSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "ROLLBACK_TIMEOUT");

        System.out.printf("""
                ================ 回补幂等性 ================
                初始库存=%d   1 个订单超时
                连续执行定时扫描 5 次
                最终库存=%d（期望 %d，不能超卖式回补）
                回补流水总量=%d（期望 +1，不能重复回补）
                不变量校验: 库存恰好回到初始值 ? %s
                不变量校验: 只回补一次 ? %s
                ============================================
                """, totalStock, stock, totalStock, rollbackSum,
                stock == totalStock, rollbackSum == 1);

        assertThat(stock).as("重复扫描不能把库存加超").isEqualTo(totalStock);
        assertThat(rollbackSum).as("状态 CAS 保证只回补一次").isEqualTo(1);
    }

    @Test
    @DisplayName("并发取消同一订单：只有一个成功，库存只回补一次")
    void concurrentCancelOnlyOneWins() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("并发取消商品", 19900L);
        int totalStock = 5;
        long activityId = createRunningActivity(productId, totalStock);

        SeckillOrderResponse order = seckillService.seckill(
                new SeckillOrderRequest(activityId, nextUserId(), 1, nextToken()));
        orderQueueService.awaitDrained(10_000);
        SeckillOrder persisted = orderRepository.findByOrderNo(order.orderNo()).orElseThrow();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder cancelled = new LongAdder();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (seckillService.cancelExpiredOrder(persisted)) {
                        cancelled.increment();
                    }
                } catch (Exception ignored) {
                    // 冲突计入失败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        int stock = activityRepository.currentStock(activityId);
        long rollbackSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "ROLLBACK_TIMEOUT");

        System.out.printf("""
                ================ 并发取消订单 ================
                并发线程=%d   初始库存=%d
                取消成功次数=%d（期望 1）
                最终库存=%d（期望 %d）
                回补流水总量=%d（期望 +1）
                不变量校验: 状态 CAS 保证只有 1 次取消生效 ? %s
                不变量校验: 库存只回补一次 ? %s
                ==============================================
                """, threads, totalStock, cancelled.sum(), stock, totalStock, rollbackSum,
                cancelled.sum() == 1, stock == totalStock && rollbackSum == 1);

        assertThat(cancelled.sum()).as("并发取消只能成功一次").isEqualTo(1);
        assertThat(stock).isEqualTo(totalStock);
        assertThat(rollbackSum).isEqualTo(1);
    }

    @Test
    @DisplayName("状态机约束：已支付订单不能取消")
    void paidOrderCannotBeCancelled() {
        cleanDatabase();
        long productId = createProduct("状态机商品", 19900L);
        long activityId = createRunningActivity(productId, 10);

        SeckillOrderResponse order = seckillService.seckill(
                new SeckillOrderRequest(activityId, nextUserId(), 1, nextToken()));
        orderQueueService.awaitDrained(10_000);
        seckillService.pay(new PayCallbackRequest(order.orderNo(), "MOCK", order.amountCents()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> seckillService.cancelOrder(order.orderNo()))
                .isInstanceOf(com.flashdeal.common.BizException.class)
                .satisfies(e -> {
                    com.flashdeal.common.BizException biz = (com.flashdeal.common.BizException) e;
                    System.out.printf("""
                            ================ 状态机约束 ================
                            订单=%s 已支付
                            尝试取消 -> 业务码=%d 消息=%s
                            不变量校验: 已支付订单不可取消(2007) ? %s
                            ============================================
                            """, order.orderNo(), biz.code(), biz.getMessage(), biz.code() == 2007);
                });

        assertThat(activityRepository.currentStock(activityId))
                .as("非法取消不能影响库存").isEqualTo(9);
    }

    @Test
    @DisplayName("活动状态校验：未开始与已结束的活动不能下单")
    void activityTimeWindowIsEnforced() {
        cleanDatabase();
        long productId = createProduct("时间窗商品", 19900L);
        long endedActivity = createEndedActivity(productId, 10);
        long pendingActivity = activityRepository.insert(productId, "未开始活动-" + System.nanoTime(),
                9900L, 10, java.time.LocalDateTime.now().plusHours(1),
                java.time.LocalDateTime.now().plusHours(2), 0);

        int endedCode = 0;
        int pendingCode = 0;
        try {
            seckillService.seckill(new SeckillOrderRequest(endedActivity, nextUserId(), 1, nextToken()));
        } catch (com.flashdeal.common.BizException e) {
            endedCode = e.code();
        }
        try {
            seckillService.seckill(new SeckillOrderRequest(pendingActivity, nextUserId(), 1, nextToken()));
        } catch (com.flashdeal.common.BizException e) {
            pendingCode = e.code();
        }

        System.out.printf("""
                ================ 活动时间窗校验 ================
                已结束活动下单 -> 业务码=%d（期望 2003）
                未开始活动下单 -> 业务码=%d（期望 2002）
                库存未受影响: 已结束=%d 未开始=%d
                不变量校验: 时间窗外的活动不能扣库存 ? %s
                ================================================
                """, endedCode, pendingCode,
                activityRepository.currentStock(endedActivity),
                activityRepository.currentStock(pendingActivity),
                endedCode == 2003 && pendingCode == 2002);

        assertThat(endedCode).isEqualTo(2003);
        assertThat(pendingCode).isEqualTo(2002);
        assertThat(activityRepository.currentStock(endedActivity)).isEqualTo(10);
        assertThat(activityRepository.currentStock(pendingActivity)).isEqualTo(10);
    }
}
