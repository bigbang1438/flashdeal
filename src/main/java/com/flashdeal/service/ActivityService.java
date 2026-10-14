package com.flashdeal.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.domain.ActivityStatus;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.SeckillOrderRepository;
import com.flashdeal.stock.StockService;

/**
 * 秒杀活动读服务：走多级缓存。
 *
 * <p>活动元数据（标题、价格、时间）是典型的「读多写极少」数据，缓存在这里收益最大。
 * 注意：<b>剩余库存不从这里读</b>——库存是强一致数据，必须走 {@link StockService}，
 * 否则会出现「缓存里的库存还没扣完，实际已经卖光」的超卖错觉。</p>
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    /** 活动详情缓存 TTL：30 秒。库存变化通过显式 invalidate 同步，TTL 只做兜底。 */
    private static final Duration DETAIL_TTL = Duration.ofSeconds(30);

    private final SeckillActivityRepository activityRepository;
    private final SeckillOrderRepository orderRepository;
    private final CacheFacade cacheFacade;
    private final StockService stockService;

    public ActivityService(SeckillActivityRepository activityRepository,
                           SeckillOrderRepository orderRepository,
                           CacheFacade cacheFacade,
                           StockService stockService) {
        this.activityRepository = activityRepository;
        this.orderRepository = orderRepository;
        this.cacheFacade = cacheFacade;
        this.stockService = stockService;
    }

    /** 读活动详情（L1 → L2 → DB）。 */
    public SeckillActivity getActivity(long activityId) {
        return cacheFacade.getOptional("activity:" + activityId, SeckillActivity.class,
                        id -> activityRepository.findById(activityId), DETAIL_TTL)
                .orElseThrow(() -> new BizException(ErrorCode.ACTIVITY_NOT_FOUND));
    }

    /** 读取活动并校验「是否可下单」。 */
    public SeckillActivity requireOrderable(long activityId) {
        SeckillActivity activity = getActivity(activityId);
        LocalDateTime now = LocalDateTime.now();
        if (activity.startTime().isAfter(now)) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (activity.endTime().isBefore(now)) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        if (activity.status() == ActivityStatus.OFFLINE.code()) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED, "活动已下线");
        }
        return activity;
    }

    /** 剩余库存（来自库存策略，不是缓存）。 */
    public int stock(long activityId) {
        return stockService.currentStock(activityId);
    }

    /** 写路径：更新活动后让缓存失效。 */
    public void evict(long activityId) {
        cacheFacade.invalidate("activity:" + activityId);
    }

    /** 清空缓存（测试夹具与运维重置用）。 */
    public void clearCache() {
        cacheFacade.clear();
    }

    /** 预热：把活动详情提前写进 L1+L2，避免开场瞬间的缓存击穿。 */
    public void warmUp(long activityId) {
        Optional<SeckillActivity> activity = activityRepository.findById(activityId);
        activity.ifPresent(a -> {
            cacheFacade.put("activity:" + activityId, a, DETAIL_TTL);
            log.debug("活动预热完成 activityId={}", activityId);
        });
    }

    /** 判断订单是否已存在（幂等重放用）。 */
    public Optional<com.flashdeal.domain.SeckillOrder> findExistingOrder(String idempotentKey) {
        return orderRepository.findByIdempotentKey(idempotentKey);
    }

    /**
     * 计算下单请求的指纹，用于幂等参数校验。
     *
     * <p>只取会影响结果的字段；故意不含时间戳，否则重复请求永远算「不同参数」。</p>
     */
    public static String requestHash(SeckillOrderRequest request) {
        return "a=" + request.activityId() + "&u=" + request.userId() + "&q=" + request.quantity();
    }
}
