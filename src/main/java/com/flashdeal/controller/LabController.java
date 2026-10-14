package com.flashdeal.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashdeal.common.ApiResponse;
import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.lock.DistributedLock;
import com.flashdeal.mq.InMemoryMessageQueue;
import com.flashdeal.mq.Message;
import com.flashdeal.ratelimit.RateLimiter;
import com.flashdeal.service.CacheFacade;
import com.flashdeal.service.OrderQueueService;
import com.flashdeal.stock.StockService;

import jakarta.annotation.PreDestroy;

/**
 * 演示与验证端点：把「锁、限流、缓存、削峰」四件事变成可以被 HTTP 直接验证的实验。
 *
 * <p>这些端点存在的意义：面试官可以自己发一个 curl 就看到结论，
 * 而不需要相信我写在 README 里的数字。</p>
 */
@RestController
@RequestMapping("/api/lab")
public class LabController {

    private final DistributedLock distributedLock;
    private final RateLimiter primaryRateLimiter;
    private final RateLimiter slidingWindowLimiter;
    private final CacheFacade cacheFacade;
    private final OrderQueueService orderQueueService;
    private final StockService stockService;

    private final ExecutorService labPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "flashdeal-lab");
        t.setDaemon(true);
        return t;
    });

    public LabController(DistributedLock distributedLock,
                         @Qualifier("primaryRateLimiter") RateLimiter primaryRateLimiter,
                         @Qualifier("slidingWindowLimiter") RateLimiter slidingWindowLimiter,
                         CacheFacade cacheFacade,
                         OrderQueueService orderQueueService,
                         StockService stockService) {
        this.distributedLock = distributedLock;
        this.primaryRateLimiter = primaryRateLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
        this.cacheFacade = cacheFacade;
        this.orderQueueService = orderQueueService;
        this.stockService = stockService;
    }

    @PreDestroy
    public void shutdown() {
        labPool.shutdownNow();
    }

    /**
     * 分布式锁正确性实验。
     *
     * <p>N 个线程并发对同一个「非原子计数器」做 +1，全程用分布式锁保护，
     * 期望结果恰好等于 N。如果锁实现有缺陷，结果会小于 N。</p>
     *
     * @param threads 并发线程数
     * @param loops   每线程循环次数
     */
    @PostMapping("/lock/verify")
    public ApiResponse<Map<String, Object>> verifyLock(@RequestParam(defaultValue = "64") int threads,
                                                       @RequestParam(defaultValue = "500") int loops)
            throws InterruptedException {
        int safeThreads = Math.min(Math.max(1, threads), 512);
        int safeLoops = Math.min(Math.max(1, loops), 100_000);
        String lockKey = "lab-counter-" + System.nanoTime();

        AtomicLong counter = new AtomicLong();
        LongAdder acquired = new LongAdder();
        LongAdder failed = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(safeThreads);

        for (int i = 0; i < safeThreads; i++) {
            labPool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < safeLoops; j++) {
                        // 用带等待时间的 tryLock：并发线程抢同一把锁时，
                        // 非阻塞版本会让绝大多数线程直接失败，测不出互斥性。
                        // 这里的语义是「最多等 5 秒拿锁，拿到后租约 5 秒」。
                        if (distributedLock.tryLock(lockKey, 5, 5, TimeUnit.SECONDS)) {
                            try {
                                acquired.increment();
                                counter.incrementAndGet();
                            } finally {
                                distributedLock.unlock(lockKey);
                            }
                        } else {
                            failed.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        long expected = (long) safeThreads * safeLoops;
        long actual = counter.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lockImpl", distributedLock.implName());
        result.put("threads", safeThreads);
        result.put("loopsPerThread", safeLoops);
        result.put("expected", expected);
        result.put("actual", actual);
        result.put("lostUpdates", expected - actual);
        result.put("correct", expected == actual);
        result.put("acquired", acquired.sum());
        result.put("failedToAcquire", failed.sum());
        result.put("elapsedMillis", elapsedMillis);
        result.put("finished", finished);
        return ApiResponse.ok(result);
    }

    /**
     * 限流实验：连续发 N 次请求，返回放行/拒绝数。
     *
     * @param algorithm token-bucket 或 sliding-window
     * @param requests  请求次数
     */
    @PostMapping("/rate-limit/probe")
    public ApiResponse<Map<String, Object>> probeRateLimit(@RequestParam(defaultValue = "token-bucket") String algorithm,
                                                           @RequestParam(defaultValue = "5000") int requests) {
        int n = Math.min(Math.max(1, requests), 1_000_000);
        RateLimiter limiter = "sliding-window".equalsIgnoreCase(algorithm) ? slidingWindowLimiter : primaryRateLimiter;
        String key = "probe:" + System.nanoTime();

        long allowed = 0;
        long rejected = 0;
        long begin = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (limiter.tryAcquire(key).allowed()) {
                allowed++;
            } else {
                rejected++;
            }
        }
        long elapsedNanos = System.nanoTime() - begin;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", limiter.algorithmName());
        result.put("requests", n);
        result.put("allowed", allowed);
        result.put("rejected", rejected);
        result.put("rejectRate", Math.round(rejected * 10000.0 / n) / 10000.0);
        result.put("elapsedMillis", elapsedNanos / 1_000_000);
        result.put("throughputPerSecond", elapsedNanos == 0 ? 0
                : Math.round(n * 1_000_000_000.0 / elapsedNanos));
        return ApiResponse.ok(result);
    }

    /**
     * 缓存命中率实验。
     *
     * @param keys       参与实验的 key 数量
     * @param accesses   总访问次数
     * @param hitRatio   期望命中的 key 占比（0~1），用于构造有区分度的负载
     */
    @PostMapping("/cache/verify")
    public ApiResponse<Map<String, Object>> verifyCache(@RequestParam(defaultValue = "100") int keys,
                                                        @RequestParam(defaultValue = "20000") int accesses,
                                                        @RequestParam(defaultValue = "0.9") double hitRatio) {
        int keyCount = Math.min(Math.max(1, keys), 100_000);
        int accessCount = Math.min(Math.max(1, accesses), 10_000_000);
        int hotKeys = Math.max(1, (int) (keyCount * Math.min(1d, Math.max(0d, hitRatio))));

        var before = cacheFacade.l1Stats();
        AtomicLong loads = new AtomicLong();
        for (int i = 0; i < accessCount; i++) {
            // 热点 key 与冷 key 混合，制造真实的命中/未命中分布
            String key = "lab:cache:" + (i % hotKeys) + (i % 10 == 0 ? ":cold" + (i % keyCount) : "");
            cacheFacade.get(key, String.class, () -> {
                loads.incrementAndGet();
                return "value-" + System.nanoTime();
            }, java.time.Duration.ofSeconds(60));
        }
        var after = cacheFacade.l1Stats();

        long hits = after.hitCount() - before.hitCount();
        long misses = after.missCount() - before.missCount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", keyCount);
        result.put("hotKeys", hotKeys);
        result.put("accesses", accessCount);
        result.put("hits", hits);
        result.put("misses", misses);
        result.put("loads", loads.get());
        result.put("hitRate", com.flashdeal.cache.CacheStats.rate(hits, misses));
        result.put("l1Size", cacheFacade.l1Size());
        result.put("l1Capacity", after.capacity());
        result.put("evictions", after.evictionCount() - before.evictionCount());
        result.put("overallHitRate", cacheFacade.overallHitRate());
        return ApiResponse.ok(result);
    }

    /**
     * 削峰实验：一次性灌入 N 条订单消息，返回队列水位变化与处理耗时。
     *
     * <p>这是「削峰前后对比数据」的来源：{@code peakBacklog} 就是被队列吸收掉的瞬时峰值。</p>
     *
     * @param count 灌入消息数
     */
    @PostMapping("/mq/peak-shaving")
    public ApiResponse<Map<String, Object>> peakShaving(@RequestParam(defaultValue = "20000") int count)
            throws InterruptedException {
        int n = Math.min(Math.max(1, count), 2_000_000);
        var before = orderQueueService.stats();
        long backlogBefore = orderQueueService.backlog();

        long begin = System.nanoTime();
        for (int i = 0; i < n; i++) {
            // 用不会与真实订单冲突的 orderNo 前缀，仅用于压测队列吞吐
            String orderNo = "LAB" + System.nanoTime() + "-" + i;
            orderQueueService.enqueue(new com.flashdeal.service.OrderCommand(
                    orderNo, 999_999L, 0L, 0L, 1, 0L, "lab", java.time.LocalDateTime.now().plusHours(1).toString(),
                    System.currentTimeMillis()));
        }
        long enqueueMillis = (System.nanoTime() - begin) / 1_000_000;

        boolean drained = orderQueueService.awaitDrained(60_000);
        long totalMillis = (System.nanoTime() - begin) / 1_000_000;
        var after = orderQueueService.stats();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", n);
        result.put("enqueueMillis", enqueueMillis);
        result.put("enqueueTps", enqueueMillis == 0 ? n : Math.round(n * 1000.0 / enqueueMillis));
        result.put("drained", drained);
        result.put("totalMillis", totalMillis);
        result.put("consumeTps", totalMillis == 0 ? n : Math.round(n * 1000.0 / totalMillis));
        result.put("backlogBefore", backlogBefore);
        result.put("peakBacklog", after == null ? -1 : after.peakBacklog());
        result.put("backlogAfter", orderQueueService.backlog());
        result.put("avgBatchSize", after == null ? 0 : after.avgBatchSize());
        result.put("avgConsumeLatencyMillis", after == null ? 0 : after.avgConsumeLatencyMillis());
        result.put("maxConsumeLatencyMillis", after == null ? 0 : after.maxConsumeLatencyMillis());
        result.put("consumerThreads", after == null ? 0 : after.consumerThreads());
        result.put("syncFallback", orderQueueService.syncFallbackCount());
        result.put("rejected", orderQueueService.rejectedCount());
        result.put("queueCapacity", after == null ? 0 : after.capacity());
        result.put("beforeStats", before);
        return ApiResponse.ok(result);
    }

    /** 当前库存策略与库存读数。 */
    @GetMapping("/stock/strategy")
    public ApiResponse<Map<String, Object>> stockStrategy(@RequestParam long activityId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", stockService.strategyName());
        result.put("activityId", activityId);
        result.put("currentStock", stockService.currentStock(activityId));
        return ApiResponse.ok(result);
    }

    /**
     * 环形缓冲裸测：直接测 {@link InMemoryMessageQueue} 所在的 MPMC 队列吞吐。
     *
     * @param count 生产条数
     */
    @PostMapping("/ring-buffer/bench")
    public ApiResponse<Map<String, Object>> ringBufferBench(@RequestParam(defaultValue = "1000000") int count)
            throws InterruptedException {
        int n = Math.min(Math.max(1, count), 20_000_000);
        int capacity = 1 << 16;
        var ring = new com.flashdeal.mq.MpmcRingBuffer<Integer>(capacity);
        LongAdder consumed = new LongAdder();
        AtomicInteger stop = new AtomicInteger();

        List<Thread> consumers = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            Thread t = new Thread(() -> {
                while (stop.get() == 0 || !ring.isEmpty()) {
                    Integer v = ring.poll();
                    if (v == null) {
                        Thread.onSpinWait();
                    } else {
                        consumed.increment();
                    }
                }
            }, "ring-consumer-" + c);
            t.setDaemon(true);
            consumers.add(t);
            t.start();
        }

        long begin = System.nanoTime();
        for (int i = 0; i < n; i++) {
            while (!ring.offer(i)) {
                Thread.onSpinWait();
            }
        }
        long produceNanos = System.nanoTime() - begin;
        stop.set(1);
        for (Thread t : consumers) {
            t.join(30_000);
        }
        long totalNanos = System.nanoTime() - begin;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("produced", n);
        result.put("consumed", consumed.sum());
        result.put("capacity", capacity);
        result.put("producerOnlyTps", produceNanos == 0 ? 0 : Math.round(n * 1_000_000_000.0 / produceNanos));
        result.put("endToEndTps", totalNanos == 0 ? 0 : Math.round(n * 1_000_000_000.0 / totalNanos));
        result.put("produceMillis", produceNanos / 1_000_000);
        result.put("totalMillis", totalNanos / 1_000_000);
        result.put("rejected", ring.rejectedCount());
        return ApiResponse.ok(result);
    }

    /** 演示：非法参数时返回统一错误体（用于人工验证全局异常处理）。 */
    @GetMapping("/error-demo")
    public ApiResponse<Void> errorDemo(@RequestParam(defaultValue = "1") int type) {
        if (type == 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这是演示用的参数错误");
        }
        throw new IllegalStateException("这是演示用的未预期异常");
    }
}
