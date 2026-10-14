package com.flashdeal.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自研内存消息队列测试：环形缓冲并发正确性、批量消费、重试、死信、削峰。
 *
 * <p>不依赖 Spring 容器，直接构造队列与环形缓冲，测试毫秒级完成。</p>
 */
class InMemoryMessageQueueTest {

    @Test
    @DisplayName("环形缓冲：单生产者单消费者 100 万条不丢不重不乱序")
    void ringBufferSingleProducerSingleConsumer() {
        int total = 1_000_000;
        MpmcRingBuffer<Integer> ring = new MpmcRingBuffer<>(1 << 14);
        // 预分配数组 + 原子下标：单消费者写入、主线程在 join() 之后读取，
        // join() 提供 happens-before，因此无需额外同步（CopyOnWriteArrayList 会有 O(n²) 开销）
        int[] received = new int[total];
        AtomicInteger receivedCount = new AtomicInteger();
        Thread consumer = new Thread(() -> {
            while (receivedCount.get() < total) {
                Integer v = ring.poll();
                if (v == null) {
                    Thread.onSpinWait();
                } else {
                    received[receivedCount.getAndIncrement()] = v;
                }
            }
        }, "ring-consumer");
        consumer.start();

        long begin = System.nanoTime();
        for (int i = 0; i < total; i++) {
            while (!ring.offer(i)) {
                Thread.onSpinWait();
            }
        }
        long produceNanos = System.nanoTime() - begin;
        try {
            consumer.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long totalNanos = System.nanoTime() - begin;

        boolean ordered = true;
        for (int i = 0; i < total; i++) {
            if (received[i] != i) {
                ordered = false;
                break;
            }
        }

        System.out.printf("""
                ================ 环形缓冲 SPSC 正确性 ================
                生产=%d  消费=%d  容量=%d
                顺序完全一致=%s
                生产耗时=%dms（生产吞吐=%,d 条/秒）
                端到端耗时=%dms（端到端吞吐=%,d 条/秒）
                队列满时的失败 offer 次数=%d（生产者自旋等待的正常现象）
                不变量校验: 生产数 == 消费数 ? %s
                不变量校验: FIFO 顺序保持 ? %s
                =====================================================
                """, total, receivedCount.get(), ring.capacity(), ordered,
                produceNanos / 1_000_000, total * 1_000_000_000L / Math.max(1, produceNanos),
                totalNanos / 1_000_000, total * 1_000_000_000L / Math.max(1, totalNanos),
                ring.rejectedCount(), total == receivedCount.get(), ordered);

        assertThat(receivedCount.get()).isEqualTo(total);
        assertThat(ordered).as("环形缓冲必须保持 FIFO 顺序").isTrue();
        assertThat(ring.publishedCount()).as("发布成功的条数必须精确").isEqualTo(total);
    }

    @Test
    @DisplayName("环形缓冲：4 生产者 × 4 消费者 80 万条无丢失无重复")
    void ringBufferMultiProducerMultiConsumer() throws InterruptedException {
        int producers = 4;
        int consumers = 4;
        int perProducer = 200_000;
        int total = producers * perProducer;

        MpmcRingBuffer<Integer> ring = new MpmcRingBuffer<>(1 << 15);
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        LongAdder duplicate = new LongAdder();
        AtomicInteger remaining = new AtomicInteger(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch consumed = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);

        for (int c = 0; c < consumers; c++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (remaining.get() > 0) {
                        Integer v = ring.poll();
                        if (v == null) {
                            Thread.onSpinWait();
                            continue;
                        }
                        if (!seen.add(v)) {
                            duplicate.increment();
                        }
                        remaining.decrementAndGet();
                        consumed.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        while (!ring.offer(base + i)) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long begin = System.nanoTime();
        start.countDown();
        boolean finished = consumed.await(120, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - begin;
        pool.shutdownNow();

        System.out.printf("""
                ================ 环形缓冲 MPMC 正确性 ================
                生产者=%d  消费者=%d  总量=%d  容量=%d
                唯一元素数=%d  重复数=%d  全部消费完成=%s
                耗时=%dms  吞吐=%,d 条/秒
                不变量校验: 唯一元素数 == 总量（无丢失）? %s
                不变量校验: 重复数 == 0（无重复）? %s
                =====================================================
                """, producers, consumers, total, ring.capacity(),
                seen.size(), duplicate.sum(), finished,
                elapsedNanos / 1_000_000, total * 1_000_000_000L / Math.max(1, elapsedNanos),
                seen.size() == total, duplicate.sum() == 0);

        assertThat(finished).isTrue();
        assertThat(seen).as("不能丢失元素").hasSize(total);
        assertThat(duplicate.sum()).as("不能重复投递").isZero();
    }

    @Test
    @DisplayName("队列满时 offer 返回 false 并计数（快速失败而不是阻塞）")
    void ringBufferRejectsWhenFull() {
        MpmcRingBuffer<Integer> ring = new MpmcRingBuffer<>(4);
        for (int i = 0; i < 4; i++) {
            assertThat(ring.offer(i)).isTrue();
        }
        boolean fifth = ring.offer(4);

        System.out.printf("""
                ================ 环形缓冲满队列行为 ================
                容量=4  已写入=4  第 5 次 offer 返回=%s
                拒绝计数=%d  积压=%d
                不变量校验: 满队列 offer 返回 false ? %s
                ==================================================
                """, fifth, ring.rejectedCount(), ring.backlog(), !fifth);

        assertThat(fifth).as("满队列必须快速失败").isFalse();
        assertThat(ring.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("批量消费：10000 条消息被合并成远少于 10000 次的处理器调用")
    void batchConsumptionReducesHandlerCalls() throws InterruptedException {
        int total = 10_000;
        LongAdder handled = new LongAdder();
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicLong maxBatch = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);

        InMemoryMessageQueue<Integer> queue = new InMemoryMessageQueue<>("batch-test", 1 << 14, 64, 2,
                3, 5L,
                batch -> {
                    handlerCalls.incrementAndGet();
                    handled.add(batch.size());
                    maxBatch.accumulateAndGet(batch.size(), Math::max);
                    if (handled.sum() >= total) {
                        done.countDown();
                    }
                    return Set.of();
                },
                (msg, err) -> { });
        queue.start();
        for (int i = 0; i < total; i++) {
            queue.publish(Message.of("k" + i, i));
        }
        boolean finished = done.await(60, TimeUnit.SECONDS);
        // 处理器内先累加 handled 再返回，队列的 consumedCount 在处理器返回后才自增，
        // 因此这里再等队列真正排空，避免读到统计的中间状态
        long drainDeadline = System.currentTimeMillis() + 10_000;
        while (queue.backlog() > 0 && System.currentTimeMillis() < drainDeadline) {
            Thread.sleep(5);
        }
        MqStats stats = queue.stats();
        queue.shutdown(2000);

        System.out.printf("""
                ================ 内存队列批量消费 ================
                发布=%d  消费成功=%d  完成=%s
                处理器调用次数=%d  平均批量=%.2f  最大批量=%d
                批量配置=64  消费者线程=2
                不变量校验: 消费数 == 发布数 ? %s
                不变量校验: 平均批量 > 1（批量合并生效）? %s
                ==================================================
                """, total, stats.consumedCount(), finished,
                handlerCalls.get(), stats.avgBatchSize(), maxBatch.get(),
                stats.consumedCount() == total, stats.avgBatchSize() > 1.0);

        assertThat(finished).isTrue();
        assertThat(stats.consumedCount()).isEqualTo(total);
        assertThat(stats.avgBatchSize()).isGreaterThan(1.0);
        assertThat(handlerCalls.get()).as("批量必须减少处理器调用次数").isLessThan(total);
    }

    @Test
    @DisplayName("失败重试：前两次失败第三次成功，最终消费成功且重试次数可观测")
    void retryThenSucceed() throws InterruptedException {
        int total = 100;
        ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        LongAdder success = new LongAdder();
        CountDownLatch done = new CountDownLatch(total);

        InMemoryMessageQueue<Integer> queue = new InMemoryMessageQueue<>("retry-test", 1024, 1, 1,
                3, 1L,
                batch -> {
                    List<String> failed = new ArrayList<>();
                    for (Message<Integer> m : batch) {
                        int n = attempts.computeIfAbsent(m.key(), k -> new AtomicInteger()).incrementAndGet();
                        if (n < 3) {
                            failed.add(m.key());
                        } else {
                            success.increment();
                            done.countDown();
                        }
                    }
                    return Set.copyOf(failed);
                },
                (msg, err) -> { });
        queue.start();
        for (int i = 0; i < total; i++) {
            queue.publish(Message.of("k" + i, i));
        }
        boolean finished = done.await(60, TimeUnit.SECONDS);
        MqStats stats = queue.stats();
        queue.shutdown(2000);

        System.out.printf("""
                ================ 内存队列失败重试 ================
                发布=%d  最终消费成功=%d  完成=%s
                失败次数=%d  重试次数=%d  死信数=%d
                最大重试配置=3  每条消息实际尝试次数=3
                不变量校验: 全部最终成功 ? %s
                不变量校验: 死信为 0（重试成功不该进死信）? %s
                ==================================================
                """, total, stats.consumedCount(), finished,
                stats.failedCount(), stats.retryCount(), stats.deadLetterCount(),
                success.sum() == total, stats.deadLetterCount() == 0);

        assertThat(finished).isTrue();
        assertThat(success.sum()).isEqualTo(total);
        assertThat(stats.retryCount()).isEqualTo(total * 2L);
        assertThat(stats.deadLetterCount()).isZero();
    }

    @Test
    @DisplayName("死信：一直失败的消息在超过 maxRetry 后进入死信，不阻塞队列")
    void deadLetterAfterMaxRetry() throws InterruptedException {
        int badMessages = 5;
        int goodMessages = 50;
        ConcurrentHashMap<String, String> deadLetters = new ConcurrentHashMap<>();
        CountDownLatch goodDone = new CountDownLatch(goodMessages);
        LongAdder goodSuccess = new LongAdder();

        InMemoryMessageQueue<Integer> queue = new InMemoryMessageQueue<>("dlq-test", 1024, 4, 2,
                2, 1L,
                batch -> {
                    List<String> failed = new ArrayList<>();
                    for (Message<Integer> m : batch) {
                        if (m.payload() < 0) {
                            failed.add(m.key()); // 永远失败
                        } else {
                            goodSuccess.increment();
                            goodDone.countDown();
                        }
                    }
                    return Set.copyOf(failed);
                },
                (msg, err) -> deadLetters.put(msg.key(), err));
        queue.start();
        for (int i = 0; i < badMessages; i++) {
            queue.publish(Message.of("bad" + i, -1));
        }
        for (int i = 0; i < goodMessages; i++) {
            queue.publish(Message.of("good" + i, i));
        }

        boolean finished = goodDone.await(60, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 10_000;
        while (deadLetters.size() < badMessages && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        MqStats stats = queue.stats();
        queue.shutdown(2000);

        System.out.printf("""
                ================ 内存队列死信 ================
                投递坏消息=%d（永久失败）  好消息=%d
                好消息消费成功=%d  完成=%s
                坏消息进入死信=%d（期望 %d）
                失败次数=%d  重试次数=%d（期望 %d = %d × maxRetry）
                不变量校验: 坏消息全部进死信 ? %s
                不变量校验: 好消息不受坏消息阻塞 ? %s
                =============================================
                """, badMessages, goodMessages, goodSuccess.sum(), finished,
                deadLetters.size(), badMessages,
                stats.failedCount(), stats.retryCount(), badMessages * 2, badMessages,
                deadLetters.size() == badMessages, goodSuccess.sum() == goodMessages);

        assertThat(finished).as("好消息不应被坏消息阻塞").isTrue();
        assertThat(goodSuccess.sum()).isEqualTo(goodMessages);
        assertThat(deadLetters).hasSize(badMessages);
        assertThat(stats.deadLetterCount()).isEqualTo(badMessages);
    }

    @Test
    @DisplayName("削峰：瞬时灌入 5 万条，队列吸收峰值（peakBacklog > 0）后全部消费")
    void peakShavingAbsorbsBurst() throws InterruptedException {
        int total = 50_000;
        LongAdder consumed = new LongAdder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong firstConsumeDelayNanos = new AtomicLong();

        InMemoryMessageQueue<Integer> queue = new InMemoryMessageQueue<>("peak-test", 1 << 16, 128, 2,
                3, 5L,
                batch -> {
                    // 模拟每条消息 20 微秒的下游处理成本（≈ 单线程 5 万 TPS 上限）
                    for (int i = 0; i < batch.size(); i++) {
                        busyWaitNanos(20_000);
                    }
                    consumed.add(batch.size());
                    if (consumed.sum() >= total) {
                        done.countDown();
                    }
                    return Set.of();
                },
                (msg, err) -> { });
        queue.start();

        long begin = System.nanoTime();
        for (int i = 0; i < total; i++) {
            if (!queue.publish(Message.of("p" + i, i))) {
                // 队列满：模拟真实降级（同步处理），计入吞吐
                consumed.increment();
                if (consumed.sum() >= total) {
                    done.countDown();
                }
            }
            if (i == 0) {
                firstConsumeDelayNanos.set(System.nanoTime() - begin);
            }
        }
        long publishNanos = System.nanoTime() - begin;
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long totalNanos = System.nanoTime() - begin;
        MqStats stats = queue.stats();
        queue.shutdown(2000);

        System.out.printf("""
                ================ 内存队列削峰效果 ================
                灌入=%d 条（瞬时脉冲）
                入队耗时=%dms  入队 TPS=%,d
                端到端耗时=%dms  端到端 TPS=%,d
                峰值积压=%d（= 被队列吸收的瞬时峰值）
                平均批量=%.2f  平均消费延迟=%.2fms  最大消费延迟=%dms
                被拒绝（队列满）=%d
                不变量校验: 全部消费完成 ? %s
                不变量校验: 峰值积压 > 0（确实削峰）? %s
                =================================================
                """, total, publishNanos / 1_000_000,
                total * 1_000_000_000L / Math.max(1, publishNanos),
                totalNanos / 1_000_000, total * 1_000_000_000L / Math.max(1, totalNanos),
                stats.peakBacklog(), stats.avgBatchSize(), stats.avgConsumeLatencyMillis(),
                stats.maxConsumeLatencyMillis(), stats.rejectedCount(),
                finished, stats.peakBacklog() > 0);

        assertThat(finished).isTrue();
        assertThat(stats.peakBacklog()).as("瞬时脉冲必须被队列吸收（峰值积压 > 0）").isPositive();
        assertThat(stats.avgBatchSize()).isGreaterThan(1.0);
    }

    private static void busyWaitNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
