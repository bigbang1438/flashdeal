package com.flashdeal.metrics;

/**
 * 单个端点的指标快照。
 *
 * @param endpoint      端点（已归一化，如 /api/seckill/orders）
 * @param totalCount    累计请求数
 * @param successCount  2xx 数
 * @param clientErrors  4xx 数
 * @param serverErrors  5xx 数
 * @param successRate   成功率 = (total - serverErrors - clientErrors) / total
 * @param qps1s         最近 1 秒 QPS
 * @param qps10s        最近 10 秒平均 QPS
 * @param peakQps1s     历史峰值 1 秒 QPS
 * @param latency       延迟统计
 */
public record EndpointMetrics(
        String endpoint,
        long totalCount,
        long successCount,
        long clientErrors,
        long serverErrors,
        double successRate,
        double qps1s,
        double qps10s,
        double peakQps1s,
        LatencyStats latency) {
}
