package com.flashdeal.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.flashdeal.mq.DeadLetterSink;
import com.flashdeal.mq.InMemoryMessageQueue;
import com.flashdeal.mq.Message;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.StockLogRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 【方案 B】内存预扣 + 异步落库扣减库存。
 *
 * <h2>为什么快</h2>
 * <p>扣减发生在进程内的 {@link AtomicLong}（每个活动一个），
 * 没有数据库往返、没有行锁竞争；请求线程只做一次 CAS 就把库存「占住」了，
 * 真正的 {@code UPDATE seckill_activity} 由后台消费者批量合并执行。</p>
 *
 * <h2>怎么保证不超卖</h2>
 * <p>{@link AtomicLong#compareAndSet} 是硬件级原子操作：线程读到 {@code current}，
 * 只有当内存值仍是 {@code current} 时才写入 {@code current - quantity}。
 * 1000 个线程并发抢 100 件库存，最终成功的必然恰好是 100 件——
 * 这与数据库 CAS 在数学上等价，只是把「行锁」换成了「CPU CAS」。</p>
 *
 * <h2>怎么保证不少卖</h2>
 * <p>内存库存是「权威余额」，只有 CAS 成功的线程才会去创建订单；
 * 因此「内存扣减数 == 订单数」是设计上的不变量，测试里会显式断言这一点。</p>
 *
 * <h2>异步落库的代价（诚实说明）</h2>
 * <ul>
 *   <li><b>最终一致</b>：数据库里的 stock 会滞后于内存，最长滞后一个消费批次；
 *       因此 {@link #currentStock} 在内存策略下读的是内存值，数据库值仅供对账；</li>
 *   <li><b>进程崩溃会丢内存态</b>：重启后内存库存从数据库重新预热，
 *       期间已扣未落库的部分会「复活」。生产环境需要 WAL 或
 *       Redis 作为共享状态，本项目在 docs/DESIGN.md 中列为已知边界；</li>
 *   <li><b>落库失败要补偿</b>：批量 UPDATE 影响行数不足时，
 *       把对应内存额度加回去并记录告警，避免「内存卖掉了、数据库没扣」。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "flashdeal.stock.mode", havingValue = "memory")
public class MemoryStockService implements StockService {

    private static final Logger log = LoggerFactory.getLogger(MemoryStockService.class);

    private final SeckillActivityRepository activityRepository;
    private final StockLogRepository stockLogRepository;

    private final Map<Long, AtomicLong> memoryStock = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private final LongAdder deductCount = new LongAdder();
    private final LongAdder rejectCount = new LongAdder();
    private final LongAdder casRetryCount = new LongAdder();
    private final LongAdder persistFailCount = new LongAdder();
    private final AtomicLong maxCasRetries = new AtomicLong();

    private InMemoryMessageQueue<StockCommand> queue;

    public MemoryStockService(SeckillActivityRepository activityRepository,
                              StockLogRepository stockLogRepository) {
        this.activityRepository = activityRepository;
        this.stockLogRepository = stockLogRepository;
    }

    /** 队列参数与订单队列独立配置，避免互相争抢消费者线程。 */
    @Value("${flashdeal.mq.stock.ring-buffer-size:65536}")
    private int ringBufferSize;
    @Value("${flashdeal.mq.stock.batch-size:128}")
    private int batchSize;
    @Value("${flashdeal.mq.stock.consumer-threads:2}")
    private int consumerThreads;
    @Value("${flashdeal.mq.stock.max-retry:5}")
    private int maxRetry;

    @PostConstruct
    public void start() {
        queue = new InMemoryMessageQueue<>("stock-persist", ringBufferSize, batchSize, consumerThreads,
                maxRetry, 20L, this::persistBatch, this::onDeadLetter);
        queue.start();
    }

    @PreDestroy
    public void stop() {
        if (queue != null) {
            queue.shutdown(5_000);
        }
    }

    /**
     * 库存预热：把数据库库存加载到内存。
     *
     * <p>必须在活动开始前完成，否则第一个请求会触发懒加载（有短暂不一致窗口）。</p>
     */
    public void preload(long activityId, int stock) {
        memoryStock.put(activityId, new AtomicLong(stock));
    }

    /** 从数据库重新加载某个活动的库存（对账/重置用）。 */
    public int reloadFromDb(long activityId) {
        int stock = activityRepository.currentStock(activityId);
        if (stock >= 0) {
            memoryStock.put(activityId, new AtomicLong(stock));
        }
        return stock;
    }

    @Override
    public DeductResult deduct(long activityId, long userId, int quantity, String orderNo) {
        AtomicLong stock = memoryStock.get(activityId);
        if (stock == null) {
            // 懒加载兜底：预热没覆盖到的活动，第一次访问时从数据库加载
            int dbStock = activityRepository.currentStock(activityId);
            if (dbStock < 0) {
                return DeductResult.fail(DeductResult.ACTIVITY_NOT_FOUND, 0);
            }
            stock = memoryStock.computeIfAbsent(activityId, k -> new AtomicLong(dbStock));
        }

        int retries = 0;
        while (true) {
            long current = stock.get();
            if (current < quantity) {
                rejectCount.increment();
                return DeductResult.fail(DeductResult.STOCK_NOT_ENOUGH, retries);
            }
            if (stock.compareAndSet(current, current - quantity)) {
                deductCount.increment();
                if (retries > 0) {
                    casRetryCount.add(retries);
                    maxCasRetries.accumulateAndGet(retries, Math::max);
                }
                // 异步落库：入队失败则同步兜底，保证内存与数据库最终一致
                StockCommand command = new StockCommand(activityId, userId, -quantity, "DEDUCT_MEMORY",
                        orderNo, sequence.incrementAndGet());
                if (queue == null || !queue.publish(Message.of(Long.toString(command.seq()), command))) {
                    persistOne(command);
                }
                return DeductResult.ok((int) (current - quantity), retries);
            }
            // CAS 失败：说明有并发修改，重读内存值重试
            retries++;
        }
    }

    @Override
    public boolean rollback(long activityId, long userId, int quantity, String orderNo, String reason) {
        AtomicLong stock = memoryStock.get(activityId);
        if (stock == null) {
            return false;
        }
        long after = stock.addAndGet(quantity);
        StockCommand command = new StockCommand(activityId, userId, quantity, reason, orderNo,
                sequence.incrementAndGet());
        if (queue == null || !queue.publish(Message.of(Long.toString(command.seq()), command))) {
            persistOne(command);
        }
        log.debug("内存回补库存 activityId={} quantity={} 回补后={} reason={}", activityId, quantity, after, reason);
        return true;
    }

    @Override
    public int currentStock(long activityId) {
        AtomicLong stock = memoryStock.get(activityId);
        if (stock != null) {
            return (int) stock.get();
        }
        int dbStock = activityRepository.currentStock(activityId);
        if (dbStock >= 0) {
            memoryStock.put(activityId, new AtomicLong(dbStock));
        }
        return dbStock;
    }

    @Override
    public String strategyName() {
        return "memory";
    }

    /**
     * 批量落库：把一批内存变更合并成 2 次数据库往返（批量 UPDATE + 批量 INSERT）。
     *
     * <p>同一活动在一个批次里出现多次时先合并 delta，进一步减少 UPDATE 次数。</p>
     *
     * @return 处理失败的消息 key 集合
     */
    Set<String> persistBatch(List<Message<StockCommand>> batch) {
        // 合并同一活动的净变动，但保留每条消息的 key 以便失败定位
        Map<Long, Integer> netDelta = new java.util.LinkedHashMap<>();
        Map<Long, Integer> lastStockAfter = new java.util.HashMap<>();
        for (Message<StockCommand> message : batch) {
            StockCommand cmd = message.payload();
            netDelta.merge(cmd.activityId(), cmd.delta(), Integer::sum);
            lastStockAfter.put(cmd.activityId(), currentStock(cmd.activityId()));
        }

        Set<String> failed = new java.util.HashSet<>();
        List<com.flashdeal.domain.StockLog> logs = new ArrayList<>(batch.size());
        for (Map.Entry<Long, Integer> entry : netDelta.entrySet()) {
            long activityId = entry.getKey();
            int delta = entry.getValue();
            int updated;
            if (delta < 0) {
                updated = activityRepository.deductStockUnsafe(activityId, -delta);
            } else {
                updated = activityRepository.rollbackStock(activityId, delta);
            }
            if (updated != 1) {
                // 落库失败：把内存额度加回去并告警，避免「内存卖了、库没扣」
                persistFailCount.increment();
                log.error("库存落库失败 activityId={} delta={}，已回补内存额度", activityId, delta);
                AtomicLong stock = memoryStock.get(activityId);
                if (stock != null && delta < 0) {
                    stock.addAndGet(-delta);
                }
                for (Message<StockCommand> message : batch) {
                    if (message.payload().activityId() == activityId) {
                        failed.add(message.key());
                    }
                }
            }
        }
        if (failed.size() == batch.size()) {
            return failed; // 整批失败，交给队列重试
        }
        for (Message<StockCommand> message : batch) {
            if (failed.contains(message.key())) {
                continue;
            }
            StockCommand cmd = message.payload();
            int stockAfter = lastStockAfter.getOrDefault(cmd.activityId(), currentStock(cmd.activityId()));
            logs.add(new com.flashdeal.domain.StockLog(null, cmd.activityId(), cmd.userId(), cmd.delta(),
                    stockAfter, cmd.reason(), cmd.orderNo(), null));
        }
        if (!logs.isEmpty()) {
            stockLogRepository.batchInsert(logs);
        }
        return failed;
    }

    /** 单条同步落库（队列满时的降级路径）。 */
    private void persistOne(StockCommand cmd) {
        try {
            int updated = cmd.delta() < 0
                    ? activityRepository.deductStockUnsafe(cmd.activityId(), -cmd.delta())
                    : activityRepository.rollbackStock(cmd.activityId(), cmd.delta());
            if (updated != 1) {
                persistFailCount.increment();
                log.error("同步落库失败 activityId={} delta={}", cmd.activityId(), cmd.delta());
                return;
            }
            stockLogRepository.insert(cmd.activityId(), cmd.userId(), cmd.delta(),
                    currentStock(cmd.activityId()), cmd.reason(), cmd.orderNo());
        } catch (Exception e) {
            persistFailCount.increment();
            log.error("同步落库异常 activityId={} delta={}", cmd.activityId(), cmd.delta(), e);
        }
    }

    private void onDeadLetter(Message<StockCommand> message, String error) {
        log.error("库存落库消息进入死信 key={} error={}", message.key(), error);
    }

    /** 等待落库队列真正排空（含在途批次）。 */
    public boolean awaitDrained(long timeoutMillis) {
        if (queue == null) {
            return true;
        }
        return queue.awaitDrained(timeoutMillis);
    }

    /** 内存预扣统计。 */
    public MemoryStockStats stats() {
        return new MemoryStockStats(
                memoryStock.size(),
                deductCount.sum(),
                rejectCount.sum(),
                casRetryCount.sum(),
                maxCasRetries.get(),
                persistFailCount.sum(),
                queue == null ? 0 : queue.backlog(),
                queue == null ? null : queue.stats());
    }

    /** 当前跟踪的活动 ID 集合（测试断言用）。 */
    public Set<Long> trackedActivities() {
        return java.util.Collections.unmodifiableSet(memoryStock.keySet());
    }

    /** 队列是否已启动。 */
    public boolean started() {
        return queue != null && queue.isRunning();
    }
}
