package com.flashdeal.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标采集测试：QPS、延迟分位、成功率、端点归一化。
 */
class MetricsCollectorTest {

    @Test
    @DisplayName("延迟分位：注入已知分布，验证 P50/P95/P99 落在正确区间")
    void latencyPercentilesAreAccurate() {
        EndpointMetricsCollector collector = new EndpointMetricsCollector("/api/test", 8192);

        // 注入 1000 个样本：1..1000 毫秒，各一次
        for (int i = 1; i <= 1000; i++) {
            collector.record(i * 1_000_000L, 200);
        }
        LatencyStats stats = collector.latencySnapshot();

        System.out.printf("""
                ================ 延迟分位准确性 ================
                样本=1000（1ms ~ 1000ms 均匀分布）
                P50=%.3fms（期望≈500）
                P95=%.3fms（期望≈950）
                P99=%.3fms（期望≈990）
                min=%.3fms  max=%.3fms  avg=%.3fms
                不变量校验: P50 落在 495~505 ? %s
                不变量校验: P95 落在 945~955 ? %s
                不变量校验: P99 落在 985~995 ? %s
                ===============================================
                """, stats.p50(), stats.p95(), stats.p99(), stats.min(), stats.max(), stats.avg(),
                stats.p50() >= 495 && stats.p50() <= 505,
                stats.p95() >= 945 && stats.p95() <= 955,
                stats.p99() >= 985 && stats.p99() <= 995);

        assertThat(stats.samples()).isEqualTo(1000);
        assertThat(stats.p50()).isBetween(495.0, 505.0);
        assertThat(stats.p95()).isBetween(945.0, 955.0);
        assertThat(stats.p99()).isBetween(985.0, 995.0);
        assertThat(stats.min()).isEqualTo(1.0);
        assertThat(stats.max()).isEqualTo(1000.0);
        assertThat(stats.avg()).isBetween(495.0, 505.0);
    }

    @Test
    @DisplayName("成功率：2xx/4xx/5xx 分别归类，成功率口径正确")
    void successRateClassification() {
        EndpointMetricsCollector collector = new EndpointMetricsCollector("/api/test", 1024);
        for (int i = 0; i < 80; i++) {
            collector.record(1_000_000L, 200);
        }
        for (int i = 0; i < 15; i++) {
            collector.record(1_000_000L, 409);
        }
        for (int i = 0; i < 5; i++) {
            collector.record(1_000_000L, 500);
        }
        EndpointMetrics metrics = collector.snapshot();

        System.out.printf("""
                ================ 成功率口径 ================
                请求=100（200×80, 409×15, 500×5）
                成功=%d  客户端错误=%d  服务端错误=%d
                成功率=%.4f（期望 0.80）
                不变量校验: 成功率 == 0.80 ? %s
                不变量校验: 分类总和 == 总数 ? %s
                ============================================
                """, metrics.successCount(), metrics.clientErrors(), metrics.serverErrors(),
                metrics.successRate(), Math.abs(metrics.successRate() - 0.8) < 1e-9,
                metrics.successCount() + metrics.clientErrors() + metrics.serverErrors() == 100);

        assertThat(metrics.totalCount()).isEqualTo(100);
        assertThat(metrics.successCount()).isEqualTo(80);
        assertThat(metrics.clientErrors()).isEqualTo(15);
        assertThat(metrics.serverErrors()).isEqualTo(5);
        assertThat(metrics.successRate()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("QPS：写入后 1 秒窗口内可读到非零 QPS")
    void qpsIsMeasured() throws InterruptedException {
        EndpointMetricsCollector collector = new EndpointMetricsCollector("/api/test", 1024);
        for (int i = 0; i < 500; i++) {
            collector.record(500_000L, 200);
        }
        double qps1s = collector.qps(1);
        double qps10s = collector.qps(10);

        System.out.printf("""
                ================ QPS 采集 ================
                1 秒内写入 500 次
                qps1s=%.2f   qps10s=%.2f   peakQps1s=%.2f
                不变量校验: qps1s > 0 ? %s
                不变量校验: qps1s >= qps10s ? %s
                =========================================
                """, qps1s, qps10s, collector.snapshot().peakQps1s(), qps1s > 0, qps1s >= qps10s);

        assertThat(qps1s).isPositive();
        assertThat(qps10s).isPositive();
        assertThat(collector.snapshot().peakQps1s()).isPositive();
    }

    @Test
    @DisplayName("并发打点：多线程写入不丢计数")
    void concurrentRecordingDoesNotLoseCounts() throws InterruptedException {
        int threads = 16;
        int perThread = 20_000;
        EndpointMetricsCollector collector = new EndpointMetricsCollector("/api/test", 8192);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        collector.record(100_000L, 200);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        long expected = (long) threads * perThread;
        System.out.printf("""
                ================ 指标并发打点 ================
                线程=%d  每线程=%d  期望总数=%d
                实际统计总数=%d  丢失=%d
                不变量校验: 计数不丢失 ? %s
                =============================================
                """, threads, perThread, expected, collector.totalCount(),
                expected - collector.totalCount(), collector.totalCount() == expected);

        assertThat(collector.totalCount()).isEqualTo(expected);
    }

    @Test
    @DisplayName("端点归一化：路径里的数字被替换成 {id}，避免指标爆炸")
    void endpointNormalization() {
        String[] inputs = {
                "/api/seckill/orders",
                "/api/seckill/orders/12345",
                "/api/admin/activities/88/status",
                "/api/dashboard/orders/999999999",
                "/"
        };
        System.out.println("================ 端点路径归一化 ================");
        for (String input : inputs) {
            String normalized = MetricsInterceptor.normalize(input);
            System.out.printf("%-40s -> %s%n", input, normalized);
        }
        System.out.println("================================================");

        assertThat(MetricsInterceptor.normalize("/api/seckill/orders")).isEqualTo("/api/seckill/orders");
        assertThat(MetricsInterceptor.normalize("/api/seckill/orders/12345")).isEqualTo("/api/seckill/orders/{id}");
        assertThat(MetricsInterceptor.normalize("/api/admin/activities/88/status"))
                .isEqualTo("/api/admin/activities/{id}/status");
        assertThat(MetricsInterceptor.normalize("/api/dashboard/orders/999999999"))
                .isEqualTo("/api/dashboard/orders/{id}");
        assertThat(MetricsInterceptor.normalize("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("环形缓冲：样本数超过容量后只保留最近 N 条")
    void ringBufferKeepsRecentSamples() {
        int bufferSize = 128;
        EndpointMetricsCollector collector = new EndpointMetricsCollector("/api/test", bufferSize);
        // 先写入 1000 个「慢」样本，再写入 128 个「快」样本
        for (int i = 0; i < 1000; i++) {
            collector.record(1_000_000_000L, 200); // 1000ms
        }
        for (int i = 0; i < bufferSize; i++) {
            collector.record(1_000_000L, 200); // 1ms
        }
        LatencyStats stats = collector.latencySnapshot();

        System.out.printf("""
                ================ 延迟环形缓冲 ================
                缓冲容量=%d  先写 1000 条 1000ms，再写 %d 条 1ms
                参与统计样本=%d
                min=%.3fms  max=%.3fms  P99=%.3fms
                不变量校验: 样本数 == 缓冲容量 ? %s
                不变量校验: 旧样本被挤出（max == 1ms）? %s
                ==============================================
                """, bufferSize, bufferSize, stats.samples(), stats.min(), stats.max(), stats.p99(),
                stats.samples() == bufferSize, stats.max() == 1.0);

        assertThat(stats.samples()).isEqualTo(bufferSize);
        assertThat(stats.max()).as("旧的高延迟样本应被挤出窗口").isEqualTo(1.0);
    }
}
