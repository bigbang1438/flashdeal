package com.flashdeal.metrics;

/**
 * 延迟统计（单位：毫秒，保留 3 位小数）。
 *
 * <p>分位数基于「最近 {@code sampleCount} 条样本的环形缓冲」计算，
 * 与 HdrHistogram 的窗口化统计口径一致：样本数够大时结果稳定，
 * 但不会把服务启动初期的冷启动延迟永久计入。</p>
 *
 * @param samples    参与统计的样本数
 * @param min        最小值
 * @param max        最大值
 * @param avg        平均值
 * @param p50        中位数
 * @param p95        95 分位
 * @param p99        99 分位
 */
public record LatencyStats(long samples, double min, double max, double avg,
                           double p50, double p95, double p99) {

    public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0, 0, 0, 0, 0);
}
