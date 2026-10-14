package com.flashdeal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.common.BizException;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.dto.SeckillOrderResponse;
import com.flashdeal.support.BaseIntegrationTest;

/**
 * 【核心测试】秒杀下单幂等性：并发重复提交只产生一个订单、只扣一次库存。
 *
 * <h2>测试场景</h2>
 * <ol>
 *   <li><b>串行重复</b>：同一 token 连续提交 10 次，只有第一次真实下单，其余返回同一订单号；</li>
 *   <li><b>并发重复</b>：32 个线程带同一个 token 同时提交，
 *       断言「恰好 1 个订单」「库存只扣 1 件」「没有重复扣减」；</li>
 *   <li><b>参数冲突</b>：同一 token 提交不同 activityId，必须被拒绝；</li>
 *   <li><b>不同 token 同用户</b>：同一用户用不同 token 抢同一活动，也只能成一单
 *       （由订单表唯一索引兜底）。</li>
 * </ol>
 */
@TestPropertySource(properties = {
        "flashdeal.stock.mode=db-cas",
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000",
        "flashdeal.rate-limit.order.user-capacity=1000000",
        "flashdeal.rate-limit.order.user-refill-per-second=1000000"
})
class IdempotentOrderTest extends BaseIntegrationTest {

    @Autowired
    private SeckillService seckillService;

    @Test
    @DisplayName("串行重复提交：同一 token 提交 10 次只产生 1 个订单，库存只扣 1 件")
    void serialDuplicateSubmissionCreatesOneOrder() {
        cleanDatabase();
        long productId = createProduct("幂等测试商品", 19900L);
        long activityId = createRunningActivity(productId, 50);
        long userId = nextUserId();
        String token = nextToken();

        List<SeckillOrderResponse> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(seckillService.seckill(
                    new SeckillOrderRequest(activityId, userId, 1, token)));
        }
        orderQueueService.awaitDrained(30_000);

        Set<String> orderNos = ConcurrentHashMap.newKeySet();
        responses.forEach(r -> orderNos.add(r.orderNo()));
        long idempotentHits = responses.stream().filter(SeckillOrderResponse::idempotent).count();
        int stock = activityRepository.currentStock(activityId);
        long orderCount = orderRepository.count(activityId, null);

        System.out.printf("""
                ================ 幂等：串行重复提交 ================
                提交次数=10（同一 token=%s）
                唯一订单号数=%d（期望 1）
                幂等命中次数=%d（期望 9）
                数据库订单数=%d（期望 1）
                剩余库存=%d（初始 50，期望 49）
                不变量校验: 只有一个订单 ? %s
                不变量校验: 库存只扣 1 件 ? %s
                ====================================================
                """, token, orderNos.size(), idempotentHits, orderCount, stock,
                orderNos.size() == 1, stock == 49);

        assertThat(orderNos).as("10 次提交必须返回同一个订单号").hasSize(1);
        assertThat(orderCount).as("数据库中只能有 1 个订单").isEqualTo(1);
        assertThat(stock).as("库存只能扣减 1 件").isEqualTo(49);
        assertThat(idempotentHits).as("后 9 次必须是幂等命中").isEqualTo(9);
        assertThat(responses.get(0).idempotent()).as("首次请求不是幂等命中").isFalse();
    }

    @Test
    @DisplayName("并发重复提交：32 线程同一 token 同时下单，只产生 1 个订单且库存只扣 1 件")
    void concurrentDuplicateSubmissionCreatesOneOrder() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("并发幂等测试商品", 19900L);
        long activityId = createRunningActivity(productId, 100);
        long userId = nextUserId();
        String token = nextToken();

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        LongAdder created = new LongAdder();
        LongAdder replayed = new LongAdder();
        LongAdder processingRejected = new LongAdder();
        LongAdder otherErrors = new LongAdder();
        Set<String> orderNos = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SeckillOrderResponse response = seckillService.seckill(
                            new SeckillOrderRequest(activityId, userId, 1, token));
                    orderNos.add(response.orderNo());
                    if (response.idempotent()) {
                        replayed.increment();
                    } else {
                        created.increment();
                    }
                } catch (BizException e) {
                    if (e.code() == 3003) {
                        processingRejected.increment();
                    } else if (e.code() == 2005) {
                        replayed.increment();
                    } else {
                        otherErrors.increment();
                    }
                } catch (Exception e) {
                    otherErrors.increment();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.currentTimeMillis();
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdownNow();

        // 订单是异步落库的：必须等队列真正排空（含在途批次）再断言数据库
        boolean drained = orderQueueService.awaitDrained(30_000);

        int stock = activityRepository.currentStock(activityId);
        long orderCount = orderRepository.count(activityId, null);
        long deductSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "DEDUCT_CAS");

        System.out.printf("""
                ================ 幂等：并发重复提交 ================
                并发线程=%d（同一 token）  耗时=%dms  完成=%s  队列已排空=%s
                真实下单成功=%d（期望 1）
                幂等重放=%d   处理中拒绝(409)=%d   其它异常=%d
                唯一订单号数=%d（期望 1）
                数据库订单数=%d（期望 1）
                剩余库存=%d（初始 100，期望 99）
                库存流水扣减总量=%d（期望 -1）
                不变量校验: 只创建 1 个订单 ? %s
                不变量校验: 库存只扣 1 件（不重复扣） ? %s
                不变量校验: 无未预期异常 ? %s
                ====================================================
                """, threads, elapsed, finished, drained,
                created.sum(), replayed.sum(), processingRejected.sum(), otherErrors.sum(),
                orderNos.size(), orderCount, stock, deductSum,
                created.sum() == 1 && orderCount == 1,
                stock == 99 && deductSum == -1,
                otherErrors.sum() == 0);

        assertThat(finished).isTrue();
        assertThat(drained).as("异步落库队列必须排空").isTrue();
        assertThat(orderCount).as("并发重复提交只能产生 1 个订单").isEqualTo(1);
        assertThat(orderNos).as("所有成功响应必须返回同一个订单号").hasSize(1);
        assertThat(created.sum()).as("只有 1 个线程真实下单").isEqualTo(1);
        assertThat(stock).as("库存只扣 1 件").isEqualTo(99);
        assertThat(deductSum).as("库存流水只有 1 条扣减").isEqualTo(-1);
        assertThat(otherErrors.sum()).as("不应有未预期异常").isZero();
    }

    @Test
    @DisplayName("幂等参数冲突：同一 token 提交不同参数必须被拒绝")
    void sameTokenDifferentPayloadIsRejected() {
        cleanDatabase();
        long productId = createProduct("冲突测试商品", 19900L);
        long activity1 = createRunningActivity(productId, 10);
        long activity2 = createRunningActivity(productId, 10);
        long userId = nextUserId();
        String token = nextToken();

        seckillService.seckill(new SeckillOrderRequest(activity1, userId, 1, token));

        assertThatThrownBy(() -> seckillService.seckill(new SeckillOrderRequest(activity2, userId, 1, token)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    System.out.printf("""
                            ================ 幂等：参数冲突 ================
                            同一 token 先提交 activityId=%d，再提交 activityId=%d
                            抛出业务码=%d  消息=%s
                            不变量校验: 冲突被识别为 3002 ? %s
                            ================================================
                            """, activity1, activity2, biz.code(), biz.getMessage(), biz.code() == 3002);
                    assertThat(biz.code()).isEqualTo(3002);
                    assertThat(biz.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        assertThat(activityRepository.currentStock(activity1)).isEqualTo(9);
        assertThat(activityRepository.currentStock(activity2)).as("冲突请求不应扣第二个活动的库存").isEqualTo(10);
    }

    @Test
    @DisplayName("不同 token 同一用户抢同一活动：唯一索引兜底，只能成一单")
    void differentTokensSameUserStillOneOrder() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("唯一索引兜底商品", 19900L);
        long activityId = createRunningActivity(productId, 100);
        long userId = nextUserId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder success = new LongAdder();
        LongAdder repeatRejected = new LongAdder();
        Set<String> orderNos = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程用不同 token，但同一 userId + 同一 activityId
                    SeckillOrderResponse response = seckillService.seckill(
                            new SeckillOrderRequest(activityId, userId, 1, nextToken()));
                    orderNos.add(response.orderNo());
                    success.increment();
                } catch (BizException e) {
                    repeatRejected.increment();
                } catch (Exception e) {
                    repeatRejected.increment();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        orderQueueService.awaitDrained(30_000);

        long orderCount = orderRepository.count(activityId, null);
        int stock = activityRepository.currentStock(activityId);
        long deductSum = stockLogRepository.sumDeltaByActivityAndReason(activityId, "DEDUCT_CAS");

        System.out.printf("""
                ================ 幂等：不同 token 同用户 ================
                并发线程=%d（同一 userId，不同 token）
                返回订单（含已存在）= %d   被拒绝=%d
                唯一订单号数=%d
                数据库订单数=%d（期望 1）
                剩余库存=%d（初始 100）
                库存流水扣减总量=%d
                不变量校验: 数据库只有 1 个订单 ? %s
                不变量校验: 库存扣减总量与订单数一致 ? %s
                ==========================================================
                """, threads, success.sum(), repeatRejected.sum(), orderNos.size(),
                orderCount, stock, deductSum,
                orderCount == 1, deductSum == -orderCount);

        assertThat(orderCount).as("唯一索引保证一个用户在同一活动只有一单").isEqualTo(1);
        assertThat(deductSum).as("库存扣减次数必须与订单数一致").isEqualTo(-orderCount);
    }

    @Test
    @DisplayName("失败后可用同一 token 重试（库存不足 -> 补库存 -> 重试成功）")
    void failedOrderCanRetryWithSameToken() {
        cleanDatabase();
        long productId = createProduct("重试测试商品", 19900L);
        long activityId = createRunningActivity(productId, 1);
        long userA = nextUserId();
        long userB = nextUserId();
        String tokenB = nextToken();

        // 用户 A 抢走唯一一件
        seckillService.seckill(new SeckillOrderRequest(activityId, userA, 1, nextToken()));
        assertThat(activityRepository.currentStock(activityId)).isZero();

        // 用户 B 用 tokenB 抢，库存不足 -> 失败
        assertThatThrownBy(() -> seckillService.seckill(new SeckillOrderRequest(activityId, userB, 1, tokenB)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo(2004));

        // 回补一件库存（模拟 A 超时未支付）
        int rolledBack = activityRepository.rollbackStock(activityId, 1);
        assertThat(rolledBack).isEqualTo(1);

        // B 用同一个 token 重试 -> 应该成功（令牌在失败时被释放）
        SeckillOrderResponse response = seckillService.seckill(
                new SeckillOrderRequest(activityId, userB, 1, tokenB));

        System.out.printf("""
                ================ 幂等：失败后同 token 重试 ================
                用户A 抢走 1 件 -> 库存=0
                用户B 用 tokenB 下单 -> 库存不足(2004) 失败
                回补 1 件 -> 用户B 用同一 tokenB 重试
                重试结果: orderNo=%s idempotent=%s
                剩余库存=%d
                不变量校验: 失败后同 token 可重试成功 ? %s
                ==========================================================
                """, response.orderNo(), response.idempotent(),
                activityRepository.currentStock(activityId), !response.idempotent());

        assertThat(response.orderNo()).isNotBlank();
        assertThat(response.idempotent()).isFalse();
        assertThat(activityRepository.currentStock(activityId)).isZero();
    }

    @Test
    @DisplayName("幂等令牌表状态流转：成功=1，失败=2")
    void tokenStatusTransitions() {
        cleanDatabase();
        long productId = createProduct("状态流转商品", 19900L);
        long activityId = createRunningActivity(productId, 1);
        long userId = nextUserId();
        String okToken = nextToken();
        String failToken = nextToken();

        seckillService.seckill(new SeckillOrderRequest(activityId, userId, 1, okToken));
        assertThatThrownBy(() -> seckillService.seckill(
                new SeckillOrderRequest(activityId, nextUserId(), 1, failToken)))
                .isInstanceOf(BizException.class);

        int okStatus = idempotentTokenRepository.findByToken(okToken).orElseThrow().status();
        int failStatus = idempotentTokenRepository.findByToken(failToken).orElseThrow().status();
        String cachedResult = idempotentTokenRepository.findByToken(okToken).orElseThrow().resultJson();

        System.out.printf("""
                ================ 幂等令牌状态机 ================
                成功令牌 status=%d（期望 1=SUCCESS）  缓存结果长度=%d
                失败令牌 status=%d（期望 2=FAILED）
                令牌总数=%d
                不变量校验: 成功令牌缓存了响应体 ? %s
                ================================================
                """, okStatus, cachedResult == null ? 0 : cachedResult.length(), failStatus,
                idempotentTokenRepository.count(), cachedResult != null && !cachedResult.isBlank());

        assertThat(okStatus).isEqualTo(1);
        assertThat(failStatus).isEqualTo(2);
        assertThat(cachedResult).as("成功令牌必须缓存响应体供重放").isNotBlank();
    }

    @Test
    @DisplayName("不同用户并发抢购：各自独立幂等，互不干扰")
    void differentUsersAreIndependent() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("独立幂等商品", 19900L);
        int stock = 50;
        long activityId = createRunningActivity(productId, stock);

        int users = 50;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int u = 0; u < users; u++) {
            final long userId = nextUserId();
            pool.submit(() -> {
                try {
                    start.await();
                    seckillService.seckill(new SeckillOrderRequest(activityId, userId, 1, nextToken()));
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        orderQueueServiceDrain();

        long orderCount = orderRepository.count(activityId, null);
        int remaining = activityRepository.currentStock(activityId);

        System.out.printf("""
                ================ 幂等：多用户独立性 ================
                并发用户=%d  初始库存=%d
                下单成功=%d  失败=%d
                数据库订单数=%d  剩余库存=%d
                不变量校验: 订单数 == 初始库存（全部卖出）? %s
                不变量校验: 订单数 == 成功数 ? %s
                ====================================================
                """, users, stock, success.get(), failed.get(), orderCount, remaining,
                orderCount == stock, orderCount == success.get());

        assertThat(orderCount).as("50 个用户抢 50 件库存，应全部卖出").isEqualTo(stock);
        assertThat(remaining).isZero();
        assertThat(success.get()).isEqualTo(stock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private OrderQueueService orderQueueService;

    /** 等待异步落库队列排空，保证后续数据库断言看得到订单。 */
    private void orderQueueServiceDrain() {
        orderQueueService.awaitDrained(30_000);
    }
}
