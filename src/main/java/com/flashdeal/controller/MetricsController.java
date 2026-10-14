package com.flashdeal.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.common.ApiResponse;
import com.flashdeal.common.PageQuery;
import com.flashdeal.common.PageResult;
import com.flashdeal.idempotent.IdempotentService;
import com.flashdeal.lock.DistributedLock;
import com.flashdeal.lock.LockStats;
import com.flashdeal.lock.redis.RedisDistributedLock;
import com.flashdeal.metrics.BusinessMetrics;
import com.flashdeal.metrics.MetricsRegistry;
import com.flashdeal.metrics.MetricsSnapshot;
import com.flashdeal.mq.DeadLetterRepository;
import com.flashdeal.mq.MqStats;
import com.flashdeal.ratelimit.RateLimiter;
import com.flashdeal.service.CacheFacade;
import com.flashdeal.service.OrderQueueService;
import com.flashdeal.stock.MemoryStockService;
import com.flashdeal.stock.StockService;

/**
 * 可观测端点（自研 /metrics，不依赖 actuator）。
 *
 * <p>输出：QPS、P50/P95/P99、成功率、缓存命中率、队列积压、锁竞争、
 * 幂等统计、业务计数器。所有数字都是运行时真实采集的，没有任何硬编码。</p>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsRegistry metricsRegistry;
    private final BusinessMetrics businessMetrics;
    private final CacheFacade cacheFacade;
    private final OrderQueueService orderQueueService;
    private final StockService stockService;
    private final IdempotentService idempotentService;
    private final DistributedLock distributedLock;
    private final RateLimiter primaryRateLimiter;
    private final DeadLetterRepository deadLetterRepository;

    public MetricsController(MetricsRegistry metricsRegistry,
                             BusinessMetrics businessMetrics,
                             CacheFacade cacheFacade,
                             OrderQueueService orderQueueService,
                             StockService stockService,
                             IdempotentService idempotentService,
                             DistributedLock distributedLock,
                             RateLimiter primaryRateLimiter,
                             DeadLetterRepository deadLetterRepository) {
        this.metricsRegistry = metricsRegistry;
        this.businessMetrics = businessMetrics;
        this.cacheFacade = cacheFacade;
        this.orderQueueService = orderQueueService;
        this.stockService = stockService;
        this.idempotentService = idempotentService;
        this.distributedLock = distributedLock;
        this.primaryRateLimiter = primaryRateLimiter;
        this.deadLetterRepository = deadLetterRepository;
    }

    /** 完整指标快照。 */
    @GetMapping
    public ApiResponse<MetricsSnapshot> metrics() {
        List<CacheStats> caches = new ArrayList<>();
        caches.add(cacheFacade.l1Stats());

        List<MqStats> queues = new ArrayList<>();
        MqStats orderQueue = orderQueueService.stats();
        if (orderQueue != null) {
            queues.add(orderQueue);
        }
        if (stockService instanceof MemoryStockService memoryStockService) {
            MqStats stockQueue = memoryStockService.stats().queueStats();
            if (stockQueue != null) {
                queues.add(stockQueue);
            }
        }

        List<LockStats> locks = new ArrayList<>();
        if (distributedLock instanceof RedisDistributedLock redisLock) {
            locks.add(redisLock.stats());
        } else if (distributedLock instanceof com.flashdeal.lock.LocalReentrantLock localLock) {
            locks.add(localLock.stats());
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("business", businessMetrics.snapshot());
        extra.put("idempotent", idempotentService.stats());
        extra.put("rateLimiter", Map.of(
                "algorithm", primaryRateLimiter.algorithmName(),
                "allowed", primaryRateLimiter.allowedCount(),
                "rejected", primaryRateLimiter.rejectedCount()));
        extra.put("cacheOverallHitRate", cacheFacade.overallHitRate());
        extra.put("cacheDbLoadCount", cacheFacade.dbLoadCount());
        extra.put("stockStrategy", stockService.strategyName());
        extra.put("lockImpl", distributedLock.implName());
        extra.put("deadLetterCount", deadLetterRepository.count(null));
        extra.put("queueBacklog", orderQueueService.backlog());
        if (stockService instanceof MemoryStockService memoryStockService) {
            extra.put("memoryStock", memoryStockService.stats());
        }

        return ApiResponse.ok(metricsRegistry.snapshot(caches, cacheFacade.l2Stats(), queues, locks, extra));
    }

    /** 精简版指标：给前端仪表盘轮询用，字段少、体积小。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        var aggregate = metricsRegistry.aggregate();
        summary.put("totalRequests", aggregate.totalCount());
        summary.put("successRate", aggregate.successRate());
        summary.put("qps1s", aggregate.qps1s());
        summary.put("qps10s", aggregate.qps10s());
        summary.put("peakQps1s", aggregate.peakQps1s());
        summary.put("clientErrors", aggregate.clientErrors());
        summary.put("serverErrors", aggregate.serverErrors());
        summary.put("cacheL1HitRate", cacheFacade.l1Stats().hitRate());
        summary.put("cacheOverallHitRate", cacheFacade.overallHitRate());
        summary.put("cacheL1Size", cacheFacade.l1Size());
        summary.put("queueBacklog", orderQueueService.backlog());
        summary.put("orderSuccess", businessMetrics.get(BusinessMetrics.ORDER_SUCCESS));
        summary.put("orderStockEmpty", businessMetrics.get(BusinessMetrics.ORDER_STOCK_EMPTY));
        summary.put("orderRateLimited", businessMetrics.get(BusinessMetrics.ORDER_RATE_LIMITED));
        summary.put("orderIdempotentReplay", businessMetrics.get(BusinessMetrics.ORDER_IDEMPOTENT_REPLAY));
        summary.put("stockStrategy", stockService.strategyName());
        return ApiResponse.ok(summary);
    }

    /** 分端点明细（含 P50/P95/P99）。 */
    @GetMapping("/endpoints")
    public ApiResponse<List<com.flashdeal.metrics.EndpointMetrics>> endpoints() {
        return ApiResponse.ok(metricsRegistry.endpointMetrics());
    }

    /** 复位指标基线（压测前调用，保证数据干净可复现）。 */
    @PostMapping("/reset")
    public ApiResponse<Boolean> reset() {
        metricsRegistry.reset();
        businessMetrics.reset();
        return ApiResponse.ok(true);
    }

    /** 死信分页查询。 */
    @GetMapping("/dead-letters")
    public ApiResponse<PageResult<DeadLetterRepository.DeadLetterRecord>> deadLetters(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<DeadLetterRepository.DeadLetterRecord> list =
                deadLetterRepository.findPage(topic, query.offset(), query.size());
        return ApiResponse.ok(PageResult.of(deadLetterRepository.count(topic), query.page(), query.size(), list));
    }
}
