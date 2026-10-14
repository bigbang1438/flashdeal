package com.flashdeal.mq;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 自研无锁 MPMC 环形缓冲（Disruptor 同族思路的 Vyukov 队列实现）。
 *
 * <h2>为什么不用 ArrayBlockingQueue</h2>
 * <p>{@code ArrayBlockingQueue} 用一把 ReentrantLock + 两个 Condition 串行化所有
 * 入队/出队，秒杀峰值下生产者与消费者互相阻塞；这里用「每个槽位自带序号」的
 * 做法把同步退化成两个 CAS，生产/消费互不阻塞，容量为 2 的幂时取模变成位与。</p>
 *
 * <h2>算法（每个槽位一个 sequence）</h2>
 * <pre>
 * 入队：读 enqueuePos → 看槽位 sequence
 *        sequence == pos        → CAS enqueuePos 成功则写入，再置 sequence = pos + 1
 *        sequence &lt;  pos        → 队列已满，返回 false（不阻塞，由上层决定降级策略）
 * 出队：读 dequeuePos → 看槽位 sequence
 *        sequence == pos + 1    → CAS dequeuePos 成功则取值，再置 sequence = pos + capacity
 * </pre>
 * 该算法保证：同一槽位不会被两个线程同时写，也不会在未发布时被读。
 *
 * <p><b>边界</b>：容量固定、不支持动态扩容；满了直接拒绝（秒杀场景宁可快速失败
 * 也不能让 Tomcat 线程阻塞在队列上）。</p>
 *
 * @param <E> 元素类型
 */
public class MpmcRingBuffer<E> {

    private final int capacity;
    private final int mask;
    private final Cell<E>[] buffer;

    private final AtomicLong enqueuePos = new AtomicLong();
    private final AtomicLong dequeuePos = new AtomicLong();

    private final LongAdder publishedCount = new LongAdder();
    private final LongAdder consumedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    @SuppressWarnings("unchecked")
    public MpmcRingBuffer(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("容量必须是 2 的幂，当前=" + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new Cell[capacity];
        for (int i = 0; i < capacity; i++) {
            buffer[i] = new Cell<>(i);
        }
    }

    /**
     * 非阻塞入队。
     *
     * @return 入队成功返回 true；队列已满返回 false（计入 rejectedCount）
     */
    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("元素不能为 null");
        }
        while (true) {
            long pos = enqueuePos.get();
            Cell<E> cell = buffer[(int) (pos & mask)];
            long seq = cell.sequence.get();
            long dif = seq - pos;
            if (dif == 0) {
                if (enqueuePos.compareAndSet(pos, pos + 1)) {
                    cell.value = item;
                    cell.sequence.set(pos + 1);
                    publishedCount.increment();
                    return true;
                }
            } else if (dif < 0) {
                // 槽位还没被消费：队列满
                rejectedCount.increment();
                return false;
            }
            // dif > 0：别的生产者刚推进了，重读位置
        }
    }

    /** 非阻塞出队，空队列返回 null。 */
    public E poll() {
        while (true) {
            long pos = dequeuePos.get();
            Cell<E> cell = buffer[(int) (pos & mask)];
            long seq = cell.sequence.get();
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (dequeuePos.compareAndSet(pos, pos + 1)) {
                    E value = cell.value;
                    cell.value = null;
                    cell.sequence.set(pos + capacity);
                    consumedCount.increment();
                    return value;
                }
            } else if (dif < 0) {
                return null;
            }
        }
    }

    /**
     * 批量出队：一次最多取 {@code maxBatch} 条，用于批量消费降低落库次数。
     *
     * @return 取到的元素列表，可能为空
     */
    public List<E> pollBatch(int maxBatch) {
        List<E> batch = new ArrayList<>(Math.min(maxBatch, 64));
        for (int i = 0; i < maxBatch; i++) {
            E item = poll();
            if (item == null) {
                break;
            }
            batch.add(item);
        }
        return batch;
    }

    /** 当前积压量（已发布 - 已消费）。 */
    public long backlog() {
        return publishedCount.sum() - consumedCount.sum();
    }

    public int capacity() {
        return capacity;
    }

    public long publishedCount() {
        return publishedCount.sum();
    }

    public long consumedCount() {
        return consumedCount.sum();
    }

    public long rejectedCount() {
        return rejectedCount.sum();
    }

    public boolean isEmpty() {
        return backlog() <= 0;
    }

    /** 环形缓冲槽位：值 + 该槽位当前的序号。 */
    private static final class Cell<E> {
        final AtomicLong sequence;
        volatile E value;

        Cell(long initialSequence) {
            this.sequence = new AtomicLong(initialSequence);
        }
    }
}
