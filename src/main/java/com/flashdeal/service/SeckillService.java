package com.flashdeal.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.domain.ActivityStatus;
import com.flashdeal.domain.OrderStatus;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.dto.PayCallbackRequest;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.dto.SeckillOrderResponse;
import com.flashdeal.idempotent.IdempotentOutcome;
import com.flashdeal.idempotent.IdempotentService;
import com.flashdeal.metrics.BusinessMetrics;
import com.flashdeal.repository.SeckillOrderRepository;
import com.flashdeal.stock.DeductResult;
import com.flashdeal.stock.StockService;

/**
 * 秒杀下单主链路。
 *
 * <h2>完整链路</h2>
 * <pre>
 * ① 限流（RateLimitInterceptor，按接口 + 按用户双维度）
 * ② 参数校验（Bean Validation + 业务校验）
 * ③ 幂等判定（IdempotentService，唯一索引抢占）
 * ④ 活动校验（ActivityService，多级缓存）
 * ⑤ 用户重复下单预检（订单表唯一索引 + 缓存标记）
 * ⑥ 库存扣减（StockService：DB CAS 或 内存预扣）
 * ⑦ 生成订单号 → 入队异步落库（OrderQueueService）
 * ⑧ 失败补偿：扣库存成功但落库失败 → 回补库存 + 标记幂等失败
 * ⑨ 支付回调 → 状态 CAS（0→1）
 * ⑩ 超时未支付 → 定时扫描 → 取消订单 + 回补库存
 * </pre>
 *
 * <h2>防超卖的三道闸门</h2>
 * <ol>
 *   <li><b>库存 CAS</b>：{@code UPDATE ... WHERE stock >= ? AND version = ?} 或内存
 *       {@code AtomicLong.compareAndSet}，保证「判断 + 扣减」原子；</li>
 *   <li><b>用户维度去重</b>：订单表唯一索引 {@code uk_order_user_activity}，
 *       一个用户在同一活动只能成一单；</li>
 *   <li><b>库存流水对账</b>：每次变动写 {@code stock_log}，
 *       「扣减总量 + 回补总量 + 剩余库存 == 总库存」可被测试直接断言。</li>
 * </ol>
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final ActivityService activityService;
    private final StockService stockService;
    private final OrderQueueService orderQueueService;
    private final IdempotentService idempotentService;
    private final SeckillOrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final BusinessMetrics metrics;
    private final JsonCodec jsonCodec;
    private final long payTimeoutSeconds;

    public SeckillService(ActivityService activityService,
                          StockService stockService,
                          OrderQueueService orderQueueService,
                          IdempotentService idempotentService,
                          SeckillOrderRepository orderRepository,
                          OrderNoGenerator orderNoGenerator,
                          BusinessMetrics metrics,
                          JsonCodec jsonCodec,
                          @Value("${flashdeal.order.pay-timeout-seconds:300}") long payTimeoutSeconds) {
        this.activityService = activityService;
        this.stockService = stockService;
        this.orderQueueService = orderQueueService;
        this.idempotentService = idempotentService;
        this.orderRepository = orderRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.metrics = metrics;
        this.jsonCodec = jsonCodec;
        this.payTimeoutSeconds = payTimeoutSeconds;
    }

    /**
     * 秒杀下单。
     *
     * <p>该方法<b>不加 {@code @Transactional}</b>：库存扣减与订单落库是两个独立的
     * 一致性单元（订单走异步队列），用一个长事务包住会把数据库连接占满，
     * 违背秒杀场景「快进快出」的设计。补偿逻辑显式写在 catch 分支里。</p>
     */
    public SeckillOrderResponse seckill(SeckillOrderRequest request) {
        String idempotentKey = request.resolvedIdempotentKey();
        String requestHash = ActivityService.requestHash(request);

        // ③ 幂等判定
        IdempotentService.IdempotentResult idem = idempotentService.begin(idempotentKey, request.userId(), requestHash);
        switch (idem.outcome()) {
            case REPLAY -> {
                metrics.increment(BusinessMetrics.ORDER_IDEMPOTENT_REPLAY);
                SeckillOrderResponse cached = JsonCodec.decodeOrThrow(idem.cachedResult(),
                        SeckillOrderResponse.class);
                return new SeckillOrderResponse(cached.orderNo(), cached.userId(), cached.activityId(),
                        cached.productId(), cached.quantity(), cached.amountCents(), cached.status(),
                        cached.statusText(), cached.createPath(), true, cached.expireAt());
            }
            case PROCESSING -> {
                metrics.increment(BusinessMetrics.ORDER_IDEMPOTENT_PROCESSING);
                throw new BizException(ErrorCode.IDEMPOTENT_PROCESSING);
            }
            case CONFLICT -> {
                metrics.increment(BusinessMetrics.ORDER_IDEMPOTENT_CONFLICT);
                throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, idem.message());
            }
            default -> {
                // ACQUIRED / RETRY_ALLOWED：继续执行业务
            }
        }

        try {
            SeckillOrderResponse response = doSeckill(request, idempotentKey);
            idempotentService.complete(idempotentKey, JsonCodec.encode(response));
            return response;
        } catch (BizException e) {
            // 业务失败：释放令牌，允许用户用同一个 token 重试（如库存不足后再抢）
            idempotentService.fail(idempotentKey);
            throw e;
        } catch (RuntimeException e) {
            idempotentService.fail(idempotentKey);
            metrics.increment(BusinessMetrics.ORDER_INTERNAL_ERROR);
            throw e;
        }
    }

    /** 真正的下单逻辑（幂等已通过）。 */
    private SeckillOrderResponse doSeckill(SeckillOrderRequest request, String idempotentKey) {
        // ④ 活动校验（走缓存）
        SeckillActivity activity = activityService.requireOrderable(request.activityId());

        // ⑤ 用户维度去重：先看订单表（快路径），命中直接返回已有订单
        Optional<SeckillOrder> existing =
                orderRepository.findByUserAndActivity(request.userId(), request.activityId());
        if (existing.isPresent()) {
            metrics.increment(BusinessMetrics.ORDER_REPEAT_USER);
            return SeckillOrderResponse.of(existing.get(), true);
        }

        // ⑤b 原子抢占「用户 × 活动」名额。
        //     订单是异步落库的，只靠「查订单表」有竞态窗口：两个请求可能都查不到订单，
        //     于是都扣了库存，最后在批量 INSERT 时撞唯一索引——那时库存已经多扣了。
        //     这里用唯一索引做同步抢占，保证「扣库存」这一步在同一用户上严格串行。
        if (!orderRepository.tryAcquireUserActivityGuard(request.userId(), request.activityId())) {
            metrics.increment(BusinessMetrics.ORDER_REPEAT_USER);
            // 抢名额失败说明同一用户正在/已经下单：短暂等待异步落库后返回已有订单
            Optional<SeckillOrder> raced = awaitOrderVisible(request.userId(), request.activityId());
            if (raced.isPresent()) {
                return SeckillOrderResponse.of(raced.get(), true);
            }
            throw new BizException(ErrorCode.REPEAT_ORDER);
        }

        boolean deducted = false;
        try {
            // ⑥ 库存扣减
            String orderNo = orderNoGenerator.next();
            DeductResult deduct = stockService.deduct(request.activityId(), request.userId(),
                    request.quantity(), orderNo);
            if (!deduct.success()) {
                releaseGuard(request);
                if (DeductResult.STOCK_NOT_ENOUGH.equals(deduct.reason())) {
                    metrics.increment(BusinessMetrics.ORDER_STOCK_EMPTY);
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
                }
                if (DeductResult.VERSION_CONFLICT.equals(deduct.reason())) {
                    metrics.increment(BusinessMetrics.ORDER_STOCK_EMPTY);
                    throw new BizException(ErrorCode.STOCK_DEDUCT_CONFLICT);
                }
                throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
            }
            deducted = true;

            long amountCents = activity.seckillPriceCents() * request.quantity();
            LocalDateTime expireAt = LocalDateTime.now().plusSeconds(payTimeoutSeconds);
            OrderCommand command = new OrderCommand(orderNo, request.userId(), request.activityId(),
                    activity.productId(), request.quantity(), amountCents, idempotentKey,
                    expireAt.toString(), System.currentTimeMillis());

            // ⑦ 异步落库；队列满则同步兜底
            boolean enqueued = orderQueueService.enqueue(command);
            if (!enqueued) {
                boolean ok = orderQueueService.persistSync(command);
                if (!ok) {
                    // ⑧ 补偿：落库失败，回补库存，避免「库存扣了但订单没有」
                    stockService.rollback(request.activityId(), request.userId(), request.quantity(),
                            orderNo, "ROLLBACK_PERSIST_FAIL");
                    metrics.increment(BusinessMetrics.STOCK_ROLLBACK);
                    releaseGuard(request);
                    throw new BizException(ErrorCode.MQ_ENQUEUE_FAILED);
                }
            }

            metrics.increment(BusinessMetrics.ORDER_SUCCESS);
            SeckillOrder order = new SeckillOrder(null, orderNo, request.userId(), request.activityId(),
                    activity.productId(), request.quantity(), amountCents, OrderStatus.PENDING_PAY.code(),
                    idempotentKey, stockService.strategyName().equals("memory") ? "MEMORY_PREDUCT" : "DB_CAS",
                    LocalDateTime.now(), null, expireAt, LocalDateTime.now());
            return SeckillOrderResponse.of(order, false);
        } catch (RuntimeException e) {
            // 扣减后发生任何异常（含入队异常）都必须回补库存并释放名额
            if (deducted) {
                stockService.rollback(request.activityId(), request.userId(), request.quantity(),
                        null, "ROLLBACK_EXCEPTION");
                metrics.increment(BusinessMetrics.STOCK_ROLLBACK);
            }
            releaseGuard(request);
            throw e;
        }
    }

    /** 释放用户×活动名额。 */
    private void releaseGuard(SeckillOrderRequest request) {
        try {
            orderRepository.releaseUserActivityGuard(request.userId(), request.activityId());
        } catch (Exception e) {
            log.warn("释放用户活动名额失败 userId={} activityId={}", request.userId(), request.activityId(), e);
        }
    }

    /**
     * 等待异步落库让订单可见（最多 500ms，20ms 一次）。
     *
     * <p>只用于「抢名额失败」这个极短竞态窗口：正常路径不需要等待。</p>
     */
    private Optional<SeckillOrder> awaitOrderVisible(long userId, long activityId) {
        for (int i = 0; i < 25; i++) {
            Optional<SeckillOrder> found = orderRepository.findByUserAndActivity(userId, activityId);
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 支付回调。
     *
     * <p>用「状态条件 UPDATE」实现幂等：{@code WHERE status = 0}，
     * 重复回调影响行数为 0，直接返回「已支付」，不会重复发货。</p>
     */
    @Transactional
    public SeckillOrderResponse pay(PayCallbackRequest request) {
        SeckillOrder order = orderRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND));

        if (order.status() == OrderStatus.PAID.code()) {
            metrics.increment(BusinessMetrics.PAY_DUPLICATE);
            return SeckillOrderResponse.of(order, true);
        }
        if (order.status() != OrderStatus.PENDING_PAY.code()) {
            metrics.increment(BusinessMetrics.PAY_FAILED);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态为 " + order.orderStatus() + "，不可支付");
        }
        if (request.paidAmountCents() > 0 && request.paidAmountCents() != order.amountCents()) {
            metrics.increment(BusinessMetrics.PAY_FAILED);
            throw new BizException(ErrorCode.PARAM_INVALID, "支付金额与订单金额不一致");
        }

        int updated = orderRepository.markPaid(request.orderNo(), LocalDateTime.now());
        if (updated == 0) {
            // 并发回调：另一个线程已经处理
            metrics.increment(BusinessMetrics.PAY_DUPLICATE);
            return SeckillOrderResponse.of(orderRepository.findByOrderNo(request.orderNo()).orElseThrow(), true);
        }
        metrics.increment(BusinessMetrics.PAY_SUCCESS);
        SeckillOrder paid = orderRepository.findByOrderNo(request.orderNo()).orElseThrow();
        return SeckillOrderResponse.of(paid, false);
    }

    /** 查询订单。 */
    public SeckillOrderResponse getOrder(String orderNo) {
        SeckillOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND));
        return SeckillOrderResponse.of(order, false);
    }

    /**
     * 取消超时未支付订单并回补库存。
     *
     * <p>用状态 CAS（0→2）保证「一个订单只会被回补一次」：
     * 定时任务与手动取消并发时，只有一个能成功改状态。</p>
     *
     * @return 是否真的取消了（false 表示已被别人处理）
     */
    @Transactional
    public boolean cancelExpiredOrder(SeckillOrder order) {
        int updated = orderRepository.markCancelled(order.orderNo());
        if (updated == 0) {
            return false;
        }
        stockService.rollback(order.activityId(), order.userId(), order.quantity(),
                order.orderNo(), "ROLLBACK_TIMEOUT");
        metrics.increment(BusinessMetrics.STOCK_ROLLBACK);
        log.info("超时未支付，已取消并回补库存 orderNo={} activityId={} quantity={}",
                order.orderNo(), order.activityId(), order.quantity());
        return true;
    }

    /** 手动取消订单（用户主动放弃）。 */
    @Transactional
    public boolean cancelOrder(String orderNo) {
        SeckillOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND));
        if (order.status() != OrderStatus.PENDING_PAY.code()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "只有待支付订单可以取消");
        }
        return cancelExpiredOrder(order);
    }

    public String stockStrategy() {
        return stockService.strategyName();
    }

    public long pendingOrderCount() {
        return orderRepository.count(null, OrderStatus.PENDING_PAY.code());
    }

    public long paidOrderCount() {
        return orderRepository.count(null, OrderStatus.PAID.code());
    }

    /** 幂等服务暴露给指标端点。 */
    public IdempotentService idempotentService() {
        return idempotentService;
    }
}
