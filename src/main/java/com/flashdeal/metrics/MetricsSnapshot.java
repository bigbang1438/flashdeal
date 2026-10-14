package com.flashdeal.metrics;

import java.util.List;
import java.util.Map;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.cache.L2Stats;
import com.flashdeal.lock.LockStats;
import com.flashdeal.mq.MqStats;

/**
 * /metrics 端点的完整响应体。
 *
 * @param timestampMillis 采集时刻
 * @param cpuCores        可用 CPU 核数
 * @param aggregate       全局聚合指标（QPS / 成功率）
 * @param endpoints       各端点明细（含 P50/P95/P99）
 * @param caches          L1 本地缓存统计（含命中率）
 * @param l2              L2 缓存统计
 * @param queues          内存队列统计（含积压、峰值积压、平均批量）
 * @param locks           分布式锁统计
 * @param extra           其它指标（如不超卖断言、库存策略等）
 */
public record MetricsSnapshot(
        long timestampMillis,
        int cpuCores,
        EndpointMetrics aggregate,
        List<EndpointMetrics> endpoints,
        List<CacheStats> caches,
        L2Stats l2,
        List<MqStats> queues,
        List<LockStats> locks,
        Map<String, Object> extra) {
}
