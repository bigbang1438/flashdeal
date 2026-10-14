package com.flashdeal.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.cache.L2Stats;
import com.flashdeal.lock.LockStats;
import com.flashdeal.mq.MqStats;

/**
 * 全局指标注册中心。
 *
 * <p>所有埋点都写这里，{@code /metrics} 端点只负责读取快照，不做任何计算，
 * 保证观测行为本身不会拖慢主链路。</p>
 */
@Component
public class MetricsRegistry {

    /** 未命中任何端点的请求会归到这个 key。 */
    public static final String UNKNOWN_ENDPOINT = "unknown";

    private final Map<String, EndpointMetricsCollector> endpoints = new ConcurrentHashMap<>();
    private final int latencyBufferSize;

    public MetricsRegistry(
            @org.springframework.beans.factory.annotation.Value("${flashdeal.metrics.latency-buffer-size:8192}")
            int latencyBufferSize) {
        this.latencyBufferSize = latencyBufferSize;
    }

    /** 记录一次请求。 */
    public void record(String endpoint, long latencyNanos, int httpStatus) {
        collector(endpoint).record(latencyNanos, httpStatus);
    }

    /** 获取（或创建）某端点的采集器。 */
    public EndpointMetricsCollector collector(String endpoint) {
        return endpoints.computeIfAbsent(endpoint == null ? UNKNOWN_ENDPOINT : endpoint,
                e -> new EndpointMetricsCollector(e, latencyBufferSize));
    }

    /** 所有端点的指标快照。 */
    public List<EndpointMetrics> endpointMetrics() {
        List<EndpointMetrics> list = new ArrayList<>(endpoints.size());
        for (EndpointMetricsCollector c : endpoints.values()) {
            list.add(c.snapshot());
        }
        list.sort((a, b) -> Long.compare(b.totalCount(), a.totalCount()));
        return list;
    }

    /** 全局聚合指标（所有端点求和）。 */
    public EndpointMetrics aggregate() {
        long total = 0, success = 0, client = 0, server = 0;
        double qps1s = 0, qps10s = 0, peak = 0;
        for (EndpointMetricsCollector c : endpoints.values()) {
            EndpointMetrics m = c.snapshot();
            total += m.totalCount();
            success += m.successCount();
            client += m.clientErrors();
            server += m.serverErrors();
            qps1s += m.qps1s();
            qps10s += m.qps10s();
            peak += m.peakQps1s();
        }
        double successRate = total == 0 ? 0d : Math.round(success * 10000.0 / total) / 10000.0;
        return new EndpointMetrics("ALL", total, success, client, server, successRate,
                Math.round(qps1s * 100.0) / 100.0,
                Math.round(qps10s * 100.0) / 100.0,
                Math.round(peak * 100.0) / 100.0,
                LatencyStats.EMPTY);
    }

    /** 清空所有指标（压测前重置基线）。 */
    public void reset() {
        for (EndpointMetricsCollector c : endpoints.values()) {
            c.reset();
        }
    }

    public int endpointCount() {
        return endpoints.size();
    }

    /**
     * 汇总视图：把各类子系统的统计拼成一个对象，直接序列化成 /metrics 的响应体。
     *
     * @param caches      L1 缓存统计
     * @param l2          L2 缓存统计
     * @param mqStats     队列统计
     * @param lockStats   锁统计
     * @param extra       其它自定义指标（如「不超卖」断言结果）
     */
    public MetricsSnapshot snapshot(List<CacheStats> caches, L2Stats l2, List<MqStats> mqStats,
                                    List<LockStats> lockStats, Map<String, Object> extra) {
        return new MetricsSnapshot(
                System.currentTimeMillis(),
                Runtime.getRuntime().availableProcessors(),
                aggregate(),
                endpointMetrics(),
                caches,
                l2,
                mqStats,
                lockStats,
                extra);
    }
}
