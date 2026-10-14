package com.flashdeal.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashdeal.common.ApiResponse;
import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.common.PageQuery;
import com.flashdeal.common.PageResult;
import com.flashdeal.domain.OrderStatus;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.dto.StockBoardItem;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.SeckillOrderRepository;
import com.flashdeal.service.OrderQueueService;
import com.flashdeal.stock.StockService;

/**
 * 运营看板接口：订单列表、库存实时看板、队列水位。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final SeckillOrderRepository orderRepository;
    private final SeckillActivityRepository activityRepository;
    private final StockService stockService;
    private final OrderQueueService orderQueueService;

    public DashboardController(SeckillOrderRepository orderRepository,
                               SeckillActivityRepository activityRepository,
                               StockService stockService,
                               OrderQueueService orderQueueService) {
        this.orderRepository = orderRepository;
        this.activityRepository = activityRepository;
        this.stockService = stockService;
        this.orderQueueService = orderQueueService;
    }

    /** 订单分页查询（可按活动、状态过滤）。 */
    @GetMapping("/orders")
    public ApiResponse<PageResult<SeckillOrder>> listOrders(
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<SeckillOrder> list = orderRepository.findPage(activityId, status, query.offset(), query.size());
        long total = orderRepository.count(activityId, status);
        return ApiResponse.ok(PageResult.of(total, query.page(), query.size(), list));
    }

    /** 订单详情。 */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<SeckillOrder> getOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND)));
    }

    /**
     * 库存实时看板。
     *
     * <p>同时返回「数据库真值」与「内存预扣余额」：在 memory 策略下两者会有短暂差异，
     * 这正好把「最终一致」这个取舍可视化出来，而不是藏起来。</p>
     */
    @GetMapping("/stock-board")
    public ApiResponse<List<StockBoardItem>> stockBoard(@RequestParam(required = false) Integer limit) {
        int size = limit == null ? 20 : Math.min(Math.max(1, limit), 100);
        List<SeckillActivity> activities = activityRepository.findPage(0, size);
        List<StockBoardItem> board = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            long orderCount = orderRepository.count(activity.id(), null);
            long paidCount = orderRepository.count(activity.id(), OrderStatus.PAID.code());
            int dbStock = activityRepository.currentStock(activity.id());
            int memoryStock = stockService.currentStock(activity.id());
            board.add(new StockBoardItem(
                    activity.id(),
                    activity.title(),
                    activity.totalStock(),
                    dbStock,
                    memoryStock,
                    activity.totalStock() - dbStock,
                    activity.soldRatio(),
                    orderCount,
                    paidCount,
                    activity.status(),
                    statusText(activity.status()),
                    stockService.strategyName()));
        }
        return ApiResponse.ok(board);
    }

    /** 队列水位：削峰效果的直接读数。 */
    @GetMapping("/queue")
    public ApiResponse<Object> queueStats() {
        return ApiResponse.ok(new QueueView(
                orderQueueService.backlog(),
                orderQueueService.enqueuedCount(),
                orderQueueService.syncFallbackCount(),
                orderQueueService.rejectedCount(),
                orderQueueService.duplicateCount(),
                orderQueueService.maxQueueLatencyMillis(),
                orderQueueService.stats()));
    }

    private static String statusText(int status) {
        return switch (status) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已结束";
            default -> "已下线";
        };
    }

    /**
     * 队列视图。
     *
     * @param backlog            当前积压
     * @param enqueuedCount      累计入队
     * @param syncFallbackCount  队列满时的同步兜底次数
     * @param rejectedCount      被拒绝（队列满）次数
     * @param duplicateCount     唯一索引拦截的重复订单数
     * @param maxQueueLatencyMillis 消息在队列中的最大停留时间
     * @param mqStats            队列完整统计
     */
    public record QueueView(long backlog, long enqueuedCount, long syncFallbackCount, long rejectedCount,
                            long duplicateCount, long maxQueueLatencyMillis,
                            com.flashdeal.mq.MqStats mqStats) {
    }
}
