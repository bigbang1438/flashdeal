package com.flashdeal.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.repository.IdempotentTokenRepository;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.SeckillOrderRepository;

/**
 * 定时任务：活动状态流转、超时订单回补库存、历史数据清理。
 *
 * <p>全部任务都用 {@code fixedDelay}（上一轮结束后再计时），
 * 避免单轮执行超时导致任务堆积。</p>
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final SeckillActivityRepository activityRepository;
    private final SeckillOrderRepository orderRepository;
    private final IdempotentTokenRepository idempotentTokenRepository;
    private final SeckillService seckillService;
    private final int expireScanBatch;

    public ScheduledTasks(SeckillActivityRepository activityRepository,
                          SeckillOrderRepository orderRepository,
                          IdempotentTokenRepository idempotentTokenRepository,
                          SeckillService seckillService,
                          @Value("${flashdeal.order.expire-scan-batch:200}") int expireScanBatch) {
        this.activityRepository = activityRepository;
        this.orderRepository = orderRepository;
        this.idempotentTokenRepository = idempotentTokenRepository;
        this.seckillService = seckillService;
        this.expireScanBatch = expireScanBatch;
    }

    /** 每秒扫描超时未支付订单，取消并回补库存。 */
    @Scheduled(fixedDelayString = "${flashdeal.order.expire-scan-interval-millis:1000}")
    public void expireUnpaidOrders() {
        List<SeckillOrder> expired = orderRepository.findExpiredPendingOrders(LocalDateTime.now(), expireScanBatch);
        if (expired.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (SeckillOrder order : expired) {
            try {
                if (seckillService.cancelExpiredOrder(order)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.warn("取消超时订单失败 orderNo={}", order.orderNo(), e);
            }
        }
        if (cancelled > 0) {
            log.info("超时订单回补完成：扫描={} 取消={}", expired.size(), cancelled);
        }
    }

    /** 每 5 秒推进活动状态（未开始 → 进行中 → 已结束）。 */
    @Scheduled(fixedDelay = 5000)
    public void advanceActivityStatus() {
        LocalDateTime now = LocalDateTime.now();
        int opened = activityRepository.openStartedActivities(now);
        int closed = activityRepository.closeExpiredActivities(now);
        if (opened > 0 || closed > 0) {
            log.info("活动状态流转：开始={} 结束={}", opened, closed);
        }
    }

    /** 每小时清理 24 小时前的幂等令牌，防止表无限增长。 */
    @Scheduled(fixedDelay = 3_600_000L)
    public void cleanupIdempotentTokens() {
        int removed = idempotentTokenRepository.deleteExpired(LocalDateTime.now().minusHours(24));
        if (removed > 0) {
            log.info("清理历史幂等令牌 {} 条", removed);
        }
    }
}
