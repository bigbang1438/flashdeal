package com.flashdeal.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单端点指标收集器：QPS + 延迟分位 + 成功率。
 *
 * <h2>QPS 怎么算的（可复现）</h2>
 * <p>维护一个长度为 60 的秒级环形数组（{@code secondSlot[epochSecond % 60]}）。
 * 每次请求把当前秒的计数 +1；读取时如果槽位的时间戳不是当前秒则视为 0。
 * {@code qps1s} = 当前秒计数，{@code qps10s} = 最近 10 秒计数 / 10。</p>
 * <p>这是「最近 N 秒」而非「自然秒」口径，因此压测中途读取也不会出现 0。</p>
 *
 * <h2>延迟分位怎么算的</h2>
 * <p>延迟样本写入一个固定长度（默认 8192）的环形数组，计算分位时把当前有效样本
 * 复制出来排序后取 {@code p50/p95/p99}。相比 HdrHistogram 精度略低，
 * 但实现完全自研、结果可解释，且 8192 个样本下排序耗时 &lt; 1ms，
 * 只会在调用 /metrics 时发生，不影响主链路。</p>
 *
 * <h2>无锁</h2>
 * <p>写路径只有 {@link LongAdder#increment()} 与 {@link AtomicLongArray#set}，
 * 没有锁、没有对象分配。</p>
 */
public class EndpointMetricsCollector {

    private static final int SECONDS_WINDOW = 60;
    private static final int QPS_SHORT_WINDOW = 10;

    private final String endpoint;
    private final int latencyBufferSize;

    private final LongAdder totalCount = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();

    private final AtomicLongArray secondCounts = new AtomicLongArray(SECONDS_WINDOW);
    private final AtomicLongArray secondStamps = new AtomicLongArray(SECONDS_WINDOW);
    private final AtomicLong peakQps = new AtomicLong();

    /** 延迟样本环形缓冲（单位：纳秒，避免浮点累加误差）。 */
    private final AtomicLongArray latencyNanos;
    private final AtomicLong latencyCursor = new AtomicLong();

    public EndpointMetricsCollector(String endpoint, int latencyBufferSize) {
        this.endpoint = endpoint;
        this.latencyBufferSize = Math.max(64, latencyBufferSize);
        this.latencyNanos = new AtomicLongArray(this.latencyBufferSize);
    }

    /** 记录一次请求。 */
    public void record(long latencyNanos, int httpStatus) {
        totalCount.increment();
        if (httpStatus >= 500) {
            serverErrors.increment();
        } else if (httpStatus >= 400) {
            clientErrors.increment();
        } else {
            successCount.increment();
        }
        markSecond();
        recordLatency(latencyNanos);
    }

    private void markSecond() {
        long epochSecond = System.currentTimeMillis() / 1000L;
        int slot = (int) Math.floorMod(epochSecond, SECONDS_WINDOW);
        if (secondStamps.get(slot) != epochSecond) {
            // 抢到槽位重置权的线程负责清零，其他线程随后 +1 也安全
            secondStamps.set(slot, epochSecond);
            secondCounts.set(slot, 0L);
        }
        long current = secondCounts.incrementAndGet(slot);
        peakQps.accumulateAndGet(current, Math::max);
    }

    private void recordLatency(long nanos) {
        long cursor = latencyCursor.getAndIncrement();
        latencyNanos.set((int) Math.floorMod(cursor, latencyBufferSize), nanos);
    }

    /** 生成指标快照。 */
    public EndpointMetrics snapshot() {
        long total = totalCount.sum();
        long success = successCount.sum();
        long client = clientErrors.sum();
        long server = serverErrors.sum();
        double successRate = total == 0 ? 0d : Math.round(success * 10000.0 / total) / 10000.0;
        return new EndpointMetrics(endpoint, total, success, client, server, successRate,
                qps(1), qps(QPS_SHORT_WINDOW), peakQps.get(), latencySnapshot());
    }

    /** 最近 n 秒的平均 QPS。 */
    public double qps(int seconds) {
        long nowSecond = System.currentTimeMillis() / 1000L;
        long sum = 0;
        for (int i = 0; i < seconds; i++) {
            long sec = nowSecond - i;
            int slot = (int) Math.floorMod(sec, SECONDS_WINDOW);
            if (secondStamps.get(slot) == sec) {
                sum += secondCounts.get(slot);
            }
        }
        return Math.round(sum * 100.0 / seconds) / 100.0;
    }

    /** 计算延迟分位。 */
    public LatencyStats latencySnapshot() {
        long written = Math.min(latencyCursor.get(), latencyBufferSize);
        if (written == 0) {
            return LatencyStats.EMPTY;
        }
        long[] samples = new long[(int) written];
        int idx = 0;
        for (int i = 0; i < latencyBufferSize && idx < written; i++) {
            long v = latencyNanos.get(i);
            if (v > 0) {
                samples[idx++] = v;
            }
        }
        if (idx == 0) {
            return LatencyStats.EMPTY;
        }
        long[] valid = idx == samples.length ? samples : Arrays.copyOf(samples, idx);
        Arrays.sort(valid);
        double avgNanos = 0;
        for (long v : valid) {
            avgNanos += v;
        }
        avgNanos /= valid.length;
        return new LatencyStats(
                valid.length,
                round3(valid[0] / 1_000_000.0),
                round3(valid[valid.length - 1] / 1_000_000.0),
                round3(avgNanos / 1_000_000.0),
                round3(percentile(valid, 0.50)),
                round3(percentile(valid, 0.95)),
                round3(percentile(valid, 0.99)));
    }

    /** 最近排名法：p99 = 排序后第 ceil(0.99 * n) 个样本。 */
    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public String endpoint() {
        return endpoint;
    }

    public long totalCount() {
        return totalCount.sum();
    }

    public long successCount() {
        return successCount.sum();
    }

    public long clientErrors() {
        return clientErrors.sum();
    }

    public long serverErrors() {
        return serverErrors.sum();
    }

    /** 复位（压测前清空基线用）。 */
    public void reset() {
        totalCount.reset();
        successCount.reset();
        clientErrors.reset();
        serverErrors.reset();
        for (int i = 0; i < SECONDS_WINDOW; i++) {
            secondCounts.set(i, 0L);
            secondStamps.set(i, 0L);
        }
        for (int i = 0; i < latencyBufferSize; i++) {
            latencyNanos.set(i, 0L);
        }
        latencyCursor.set(0);
        peakQps.set(0);
    }
}
