package com.flashdeal.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flashdeal.domain.OrderStatus;
import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.mq.DeadLetterSink;
import com.flashdeal.mq.InMemoryMessageQueue;
import com.flashdeal.mq.Message;
import com.flashdeal.mq.MqStats;
import com.flashdeal.repository.SeckillOrderRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 订单落库队列：秒杀主链路的「削峰填谷」装置。
 *
 * <h2>为什么需要它</h2>
 * <p>秒杀峰值是「瞬时脉冲」：10 秒钟涌进 5 万个请求，但数据库稳定处理能力只有
 * 每秒几千次 INSERT。如果让 HTTP 线程直接写库，Tomcat 线程池会被慢 SQL 占满，
 * 请求排队、超时、雪崩。</p>
 *
 * <p>这里让 HTTP 线程只做一次入队（O(1)，无锁），订单写入交给固定数量的消费者线程
 * 按自己的节奏批量 INSERT。队列的 {@code peakBacklog} 就是被吸收掉的峰值水位。</p>
 *
 * <h2>一致性保障</h2>
 * <ul>
 *   <li>入队成功 = 订单一定最终落库（有重试 + 死信兜底）；</li>
 *   <li>入队失败（队列满）= 立刻同步落库，绝不给用户「下单成功但订单丢失」的错觉；</li>
 *   <li>订单表唯一索引 {@code uk_order_user_activity} 兜住重复。</li>
 * </ul>
 */
@Service
public class OrderQueueService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueService.class);

    private final SeckillOrderRepository orderRepository;

    private InMemoryMessageQueue<OrderCommand> queue;

    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder syncFallbackCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();
    private final LongAdder duplicateCount = new LongAdder();
    private final AtomicLong maxQueueLatencyMillis = new AtomicLong();

    public OrderQueueService(SeckillOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Value("${flashdeal.mq.order.ring-buffer-size:65536}")
    private int ringBufferSize;
    @Value("${flashdeal.mq.order.batch-size:64}")
    private int batchSize;
    @Value("${flashdeal.mq.order.consumer-threads:4}")
    private int consumerThreads;
    @Value("${flashdeal.mq.order.max-retry:3}")
    private int maxRetry;
    @Value("${flashdeal.mq.order.retry-backoff-millis:20}")
    private long retryBackoffMillis;

    @PostConstruct
    public void start() {
        queue = new InMemoryMessageQueue<>("order-persist", ringBufferSize, batchSize, consumerThreads,
                maxRetry, retryBackoffMillis, this::persistBatch, this::onDeadLetter);
        queue.start();
    }

    @PreDestroy
    public void stop() {
        if (queue != null) {
            queue.shutdown(10_000);
        }
    }

    /**
     * 异步入队。
     *
     * @return 入队成功 true；队列满返回 false，调用方应同步落库
     */
    public boolean enqueue(OrderCommand command) {
        boolean ok = queue.publish(Message.of(command.orderNo(), command));
        if (ok) {
            enqueuedCount.increment();
        } else {
            rejectedCount.increment();
        }
        return ok;
    }

    /** 同步落库（队列满时的降级路径）。 */
    public boolean persistSync(OrderCommand command) {
        syncFallbackCount.increment();
        return insertOne(command);
    }

    /** 批量落库：一次 batchUpdate 写入整批订单。 */
    Set<String> persistBatch(List<Message<OrderCommand>> batch) {
        List<SeckillOrder> orders = new ArrayList<>(batch.size());
        for (Message<OrderCommand> message : batch) {
            orders.add(toOrder(message.payload()));
            long latency = System.currentTimeMillis() - message.payload().enqueueAt();
            maxQueueLatencyMillis.accumulateAndGet(latency, Math::max);
        }
        Set<String> failed = new HashSet<>();
        try {
            int inserted = orderRepository.batchInsert(orders);
            if (inserted < orders.size()) {
                duplicateCount.add(orders.size() - inserted);
            }
        } catch (Exception e) {
            log.warn("批量落库失败，整批重试 批量={} error={}", orders.size(), e.getMessage());
            for (Message<OrderCommand> message : batch) {
                failed.add(message.key());
            }
        }
        return failed;
    }

    private boolean insertOne(OrderCommand command) {
        try {
            orderRepository.insert(toOrder(command));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            duplicateCount.increment();
            log.debug("订单已存在（唯一索引拦截）orderNo={}", command.orderNo());
            return true;
        } catch (Exception e) {
            log.error("同步落库失败 orderNo={}", command.orderNo(), e);
            return false;
        }
    }

    private SeckillOrder toOrder(OrderCommand command) {
        LocalDateTime now = LocalDateTime.now();
        return new SeckillOrder(null, command.orderNo(), command.userId(), command.activityId(),
                command.productId(), command.quantity(), command.amountCents(),
                OrderStatus.PENDING_PAY.code(), command.idempotentKey(), "MEMORY_PREDUCT",
                now, null, LocalDateTime.parse(command.expireAt()), now);
    }

    private void onDeadLetter(Message<OrderCommand> message, String error) {
        log.error("订单落库消息进入死信 orderNo={} error={}", message.key(), error);
    }

    /**
     * 等待队列真正排空（含在途批次）。
     *
     * <p>不能只看 backlog：消费者会先把一批消息 poll 出来（backlog 已为 0），
     * 再调用处理器落库。队列内部用「published == consumed + deadLetter」判断
     * 是否还有在途批次。</p>
     */
    public boolean awaitDrained(long timeoutMillis) {
        if (queue == null) {
            return true;
        }
        return queue.awaitDrained(timeoutMillis);
    }

    public MqStats stats() {
        return queue == null ? null : queue.stats();
    }

    public long backlog() {
        return queue == null ? 0 : queue.backlog();
    }

    public long enqueuedCount() {
        return enqueuedCount.sum();
    }

    public long syncFallbackCount() {
        return syncFallbackCount.sum();
    }

    public long rejectedCount() {
        return rejectedCount.sum();
    }

    public long duplicateCount() {
        return duplicateCount.sum();
    }

    public long maxQueueLatencyMillis() {
        return maxQueueLatencyMillis.get();
    }

    public boolean running() {
        return queue != null && queue.isRunning();
    }
}
