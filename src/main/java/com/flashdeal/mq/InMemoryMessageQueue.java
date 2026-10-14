package com.flashdeal.mq;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自研内存消息队列：环形缓冲 + 批量消费 + 失败重试 + 死信。
 *
 * <h2>削峰原理</h2>
 * <p>HTTP 线程只做「入队」这一件 O(1) 的事就立刻返回，落库/下游调用交给固定数量的
 * 消费者线程按自己的吞吐慢慢消费。峰值流量被环形缓冲吸收，
 * {@code peakBacklog} 就是「削峰水位」的直接证据。</p>
 *
 * <h2>批量消费</h2>
 * <p>消费者每次 {@code pollBatch(batchSize)} 取一批，交给处理器批量落库，
 * 把 N 次数据库往返压成 1 次。实测平均批量大小见 {@code avgBatchSize}。</p>
 *
 * <h2>重试与死信</h2>
 * <p>处理失败的消息带 {@code attempt+1} 重新入队，最多 {@code maxRetry} 次；
 * 超过后写入 {@link DeadLetterSink}，不再阻塞主链路。</p>
 *
 * <h2>优雅停机</h2>
 * <p>{@link #shutdown(long)} 会先停止接收新消息，再给消费者时间把队列排空。</p>
 *
 * @param <T> 载荷类型
 */
public class InMemoryMessageQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageQueue.class);

    private final String topic;
    private final MpmcRingBuffer<Message<T>> ring;
    private final int batchSize;
    private final int consumerThreads;
    private final int maxRetry;
    private final long retryBackoffMillis;
    private final MessageHandler<T> handler;
    private final DeadLetterSink<T> deadLetterSink;

    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final LongAdder consumedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final LongAdder retryCount = new LongAdder();
    private final LongAdder deadLetterCount = new LongAdder();
    private final LongAdder batchCount = new LongAdder();
    private final LongAdder latencySumMillis = new LongAdder();
    private final LongAdder latencySamples = new LongAdder();
    private final AtomicLong maxLatencyMillis = new AtomicLong();
    private final AtomicLong peakBacklog = new AtomicLong();

    public InMemoryMessageQueue(String topic, int ringCapacity, int batchSize, int consumerThreads,
                                int maxRetry, long retryBackoffMillis,
                                MessageHandler<T> handler, DeadLetterSink<T> deadLetterSink) {
        this.topic = topic;
        this.ring = new MpmcRingBuffer<>(ringCapacity);
        this.batchSize = Math.max(1, batchSize);
        this.consumerThreads = Math.max(1, consumerThreads);
        this.maxRetry = Math.max(0, maxRetry);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        this.handler = handler;
        this.deadLetterSink = deadLetterSink;
    }

    /** 启动消费者线程。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < consumerThreads; i++) {
            Thread worker = new Thread(this::consumeLoop, "flashdeal-mq-" + topic + "-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        log.info("内存队列已启动 topic={} 容量={} 批量={} 消费者={} 最大重试={}",
                topic, ring.capacity(), batchSize, consumerThreads, maxRetry);
    }

    /**
     * 非阻塞入队。
     *
     * @return 成功 true；队列满返回 false（调用方需降级，如直接同步处理或返回 503）
     */
    public boolean publish(Message<T> message) {
        boolean ok = ring.offer(message);
        if (ok) {
            long backlog = ring.backlog();
            peakBacklog.accumulateAndGet(backlog, Math::max);
        }
        return ok;
    }

    /** 优雅停机：先停接收，再等待排空。 */
    public void shutdown(long awaitMillis) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        awaitDrained(awaitMillis);
        for (Thread worker : workers) {
            worker.interrupt();
        }
        log.info("内存队列已停止 topic={} 剩余积压={}", topic, ring.backlog());
    }

    /**
     * 等待队列「真正处理完」。
     *
     * <p><b>为什么不能只看 backlog</b>：消费者是先把一批消息 poll 出来（此时 backlog 已经变 0），
     * 再去调用处理器。所以 {@code backlog == 0} 并不代表处理完成，
     * 中间存在一个「批次在途」的窗口。</p>
     *
     * <p>这里同时要求 {@code publishedCount == consumedCount + deadLetterCount}：
     * 这两个计数都在处理器<b>返回之后</b>才自增，因此等式成立就意味着
     * 没有任何批次处于在途状态。</p>
     *
     * @return 是否在超时前排空
     */
    public boolean awaitDrained(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (ring.backlog() == 0
                    && ring.publishedCount() == consumedCount.sum() + deadLetterCount.sum()) {
                return true;
            }
            sleep(2);
        }
        return ring.backlog() == 0
                && ring.publishedCount() == consumedCount.sum() + deadLetterCount.sum();
    }

    /** 消费者主循环：批量取出、批量处理、逐条判定成败。 */
    private void consumeLoop() {
        while (running.get() || ring.backlog() > 0) {
            List<Message<T>> batch = ring.pollBatch(batchSize);
            if (batch.isEmpty()) {
                sleep(1);
                continue;
            }
            batchCount.increment();
            Set<String> failedKeys;
            String batchError = null;
            try {
                Set<String> result = handler.handleBatch(batch);
                failedKeys = result == null ? Set.of() : result;
            } catch (Exception e) {
                // 整批失败：全部重试
                failedKeys = batch.stream().map(Message::key).collect(java.util.stream.Collectors.toSet());
                batchError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("批量消费失败 topic={} 批量={} error={}", topic, batch.size(), batchError);
            }
            for (Message<T> message : batch) {
                if (failedKeys.contains(message.key())) {
                    handleFailure(message, batchError == null ? "处理器标记失败" : batchError);
                } else {
                    recordSuccess(message);
                }
            }
        }
    }

    /** 失败处理：重试或死信。 */
    private void handleFailure(Message<T> message, String error) {
        failedCount.increment();
        if (message.attempt() <= maxRetry) {
            retryCount.increment();
            if (retryBackoffMillis > 0) {
                sleep(retryBackoffMillis);
            }
            if (!ring.offer(message.nextAttempt())) {
                // 重试入队都满了，直接落死信，避免消息丢失
                toDeadLetter(message, "重试入队失败(队列满): " + error);
            }
        } else {
            toDeadLetter(message, error);
        }
    }

    private void recordSuccess(Message<T> message) {
        consumedCount.increment();
        long latency = message.latencyMillis();
        latencySumMillis.add(latency);
        latencySamples.increment();
        maxLatencyMillis.accumulateAndGet(latency, Math::max);
    }

    private void toDeadLetter(Message<T> message, String error) {
        deadLetterCount.increment();
        log.warn("消息进入死信 topic={} key={} attempt={} error={}", topic, message.key(), message.attempt(), error);
        if (deadLetterSink != null) {
            try {
                deadLetterSink.save(message, error);
            } catch (Exception e) {
                log.error("死信落盘失败 topic={} key={}", topic, message.key(), e);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 统计快照。 */
    public MqStats stats() {
        long batches = batchCount.sum();
        long consumed = consumedCount.sum();
        long samples = latencySamples.sum();
        return new MqStats(
                topic,
                ring.capacity(),
                ring.publishedCount(),
                consumed,
                failedCount.sum(),
                retryCount.sum(),
                deadLetterCount.sum(),
                ring.rejectedCount(),
                ring.backlog(),
                peakBacklog.get(),
                batches == 0 ? 0d : Math.round(consumed * 100.0 / batches) / 100.0,
                samples == 0 ? 0d : Math.round(latencySumMillis.sum() * 100.0 / samples) / 100.0,
                maxLatencyMillis.get(),
                consumerThreads,
                running.get());
    }

    public String topic() {
        return topic;
    }

    public long backlog() {
        return ring.backlog();
    }

    public boolean isRunning() {
        return running.get();
    }
}
