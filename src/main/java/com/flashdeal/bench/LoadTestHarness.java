package com.flashdeal.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 自研压测工具（<b>零依赖</b>：只用 JDK 21 的 {@link HttpClient}）。
 *
 * <h2>为什么不用 k6 / JMeter / wrk</h2>
 * <p>本机没有 k6，也不希望为了压测引入额外工具链。用 JDK 自带 HttpClient
 * 写压测器有三个好处：</p>
 * <ol>
 *   <li>零安装成本，{@code java -cp target/classes com.flashdeal.bench.LoadTestHarness} 直接跑；</li>
 *   <li>压测逻辑与业务断言在同一个仓库里，数据可复现、可被 CI 重跑；</li>
 *   <li>能精确控制「预热—计时—统计」三个阶段，避免把 JIT 预热算进结果。</li>
 * </ol>
 *
 * <h2>统计口径（README 的数字直接来自这里）</h2>
 * <ul>
 *   <li><b>QPS</b> = 计时窗口内完成的请求数 / 窗口秒数；</li>
 *   <li><b>P50/P95/P99</b>：记录每个请求的纳秒延迟，排序后取
 *       {@code ceil(p × n) - 1} 位置的值（与 {@code EndpointMetricsCollector} 口径一致）；</li>
 *   <li><b>错误率</b> = (非预期状态码数) / 总请求数。
 *       注意：秒杀场景下 409（库存不足 / 重复下单）和 429（限流）是<b>业务预期</b>结果，
 *       是否算错误由 {@code --count-4xx-as-error} 决定，默认不算，
 *       这样「错误率」反映的是系统故障而不是业务拒绝；</li>
 *   <li>预热请求不计入统计。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp target/classes com.flashdeal.bench.LoadTestHarness \
 *      --url http://127.0.0.1:8080/api/seckill/orders \
 *      --scenario seckill --activity-id 1 --stock 100 \
 *      --threads 200 --requests 20000 --warmup 2000
 * </pre>
 */
public final class LoadTestHarness {

    private LoadTestHarness() {
    }

    /** 命令行参数。 */
    record Options(String baseUrl, String scenario, long activityId, int stock, int threads, int requests,
                   int warmup, boolean count4xxAsError, long userBase, boolean verbose) {
    }

    public static void main(String[] args) throws Exception {
        Options options = parseArgs(args);
        System.out.println("""
                ╔══════════════════════════════════════════════════════════════════╗
                ║  FlashDeal 自研压测工具（JDK HttpClient，零外部依赖）              ║
                ╚══════════════════════════════════════════════════════════════════╝""");
        System.out.printf("""
                目标地址   : %s
                压测场景   : %s
                并发线程   : %d
                总请求数   : %d
                预热请求   : %d
                4xx 计错误 : %s
                ──────────────────────────────────────────────────────────────────%n""",
                options.baseUrl, options.scenario, options.threads, options.requests,
                options.warmup, options.count4xxAsError);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(Math.min(options.threads + 8, 512)))
                .build();

        try {
            warmup(client, options);
            Result result = run(client, options);
            printReport(options, result);
        } finally {
            client.close();
        }
    }

    /** 预热：让 JIT 编译热点路径、让连接池建立连接，结果不计入统计。 */
    private static void warmup(HttpClient client, Options options) throws Exception {
        if (options.warmup <= 0) {
            return;
        }
        System.out.printf("预热中（%d 个请求）...%n", options.warmup);
        List<HttpRequest> requests = new ArrayList<>(options.warmup);
        for (int i = 0; i < options.warmup; i++) {
            requests.add(buildRequest(options, options.userBase + 900_000_000L + i, i));
        }
        int threads = Math.min(options.threads, options.warmup);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(requests.size());
        for (HttpRequest request : requests) {
            pool.submit(() -> {
                try {
                    client.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                    // 预热阶段的失败不影响统计
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        System.out.println("预热完成。");
    }

    /** 正式压测。 */
    private static Result run(HttpClient client, Options options) throws Exception {
        int threads = options.threads;
        int totalRequests = options.requests;
        int perThread = totalRequests / threads;
        int remainder = totalRequests % threads;

        long[] latencies = new long[totalRequests];
        AtomicLong latencyCursor = new AtomicLong();
        Map<Integer, LongAdder> statusCounts = new LinkedHashMap<>();
        LongAdder transportErrors = new LongAdder();
        LongAdder successBusiness = new LongAdder();
        LongAdder stockEmpty = new LongAdder();
        LongAdder rateLimited = new LongAdder();
        LongAdder repeatOrder = new LongAdder();
        LongAdder otherBusiness = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threads);
        AtomicBoolean stop = new AtomicBoolean(false);

        for (int t = 0; t < threads; t++) {
            final int count = perThread + (t < remainder ? 1 : 0);
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < count && !stop.get(); i++) {
                        // 每个请求一个独立用户：模拟真实秒杀（一人一单）
                        long userId = options.userBase + (long) threadIndex * 1_000_000L + i;
                        HttpRequest request = buildRequest(options, userId, i);
                        long begin = System.nanoTime();
                        try {
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long latency = System.nanoTime() - begin;
                            latencies[(int) latencyCursor.getAndIncrement()] = latency;

                            int status = response.statusCode();
                            statusCounts.computeIfAbsent(status, k -> new LongAdder()).increment();
                            classify(response, status, successBusiness, stockEmpty, rateLimited,
                                    repeatOrder, otherBusiness);
                        } catch (Exception e) {
                            transportErrors.increment();
                            latencies[(int) latencyCursor.getAndIncrement()] = System.nanoTime() - begin;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        System.out.println("压测开始...");
        long beginNanos = System.nanoTime();
        startGate.countDown();
        boolean finished = finishGate.await(600, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - beginNanos;
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        int recorded = (int) Math.min(latencyCursor.get(), totalRequests);
        long[] valid = Arrays.copyOf(latencies, recorded);
        Arrays.sort(valid);

        long expectedErrors = 0;
        for (Map.Entry<Integer, LongAdder> e : statusCounts.entrySet()) {
            int status = e.getKey();
            boolean isError = status >= 500 || (options.count4xxAsError && status >= 400);
            if (isError) {
                expectedErrors += e.getValue().sum();
            }
        }
        expectedErrors += transportErrors.sum();

        return new Result(recorded, elapsedNanos, valid, statusCounts, transportErrors.sum(),
                expectedErrors, successBusiness.sum(), stockEmpty.sum(), rateLimited.sum(),
                repeatOrder.sum(), otherBusiness.sum(), finished);
    }

    /** 按业务码分类响应。 */
    private static void classify(HttpResponse<String> response, int status,
                                 LongAdder success, LongAdder stockEmpty, LongAdder rateLimited,
                                 LongAdder repeatOrder, LongAdder other) {
        if (status == 201 || status == 200) {
            success.increment();
            return;
        }
        String body = response.body();
        if (status == 429) {
            rateLimited.increment();
        } else if (body != null && body.contains("\"code\":2004")) {
            stockEmpty.increment();
        } else if (body != null && body.contains("\"code\":2005")) {
            repeatOrder.increment();
        } else {
            other.increment();
        }
    }

    /** 构造请求。 */
    private static HttpRequest buildRequest(Options options, long userId, int index) {
        String path = switch (options.scenario) {
            case "seckill" -> "/api/seckill/orders";
            case "activity-read" -> "/api/admin/activities/" + options.activityId;
            case "stock-board" -> "/api/dashboard/stock-board?limit=20";
            case "metrics" -> "/api/metrics/summary";
            default -> throw new IllegalArgumentException("未知场景: " + options.scenario);
        };
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(options.baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-User-Id", Long.toString(userId))
                .header("Content-Type", "application/json");

        if ("seckill".equals(options.scenario)) {
            String body = "{\"activityId\":" + options.activityId
                    + ",\"userId\":" + userId
                    + ",\"quantity\":1"
                    + ",\"idempotentToken\":\"bench-" + userId + "\"}";
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    /** 输出报告。 */
    private static void printReport(Options options, Result result) {
        double elapsedSeconds = result.elapsedNanos / 1_000_000_000.0;
        double qps = elapsedSeconds == 0 ? 0 : result.recorded / elapsedSeconds;
        double errorRate = result.recorded == 0 ? 0 : result.errors * 1.0 / result.recorded;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        压 测 结 果 报 告                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("""
                场景              : %s
                并发线程          : %d
                完成请求数        : %d
                计时窗口          : %.3f s
                ──────────────────────────────────────────────────────────────────
                QPS               : %.2f
                平均延迟          : %.3f ms
                最小延迟          : %.3f ms
                最大延迟          : %.3f ms
                P50               : %.3f ms
                P90               : %.3f ms
                P95               : %.3f ms
                P99               : %.3f ms
                P999              : %.3f ms
                ──────────────────────────────────────────────────────────────────
                错误数            : %d
                错误率            : %.4f%%（%.4f）
                传输层异常        : %d
                ──────────────────────────────────────────────────────────────────
                业务结果分布:
                  下单成功(201)       : %d
                  库存不足(2004/409)  : %d
                  重复下单(2005/409)  : %d
                  被限流(429)         : %d
                  其它                : %d
                HTTP 状态码分布   : %s
                ──────────────────────────────────────────────────────────────────
                全部线程完成      : %s
                """,
                options.scenario, options.threads, result.recorded, elapsedSeconds,
                qps,
                percentile(result.sorted, 0.0),
                percentile(result.sorted, -1.0),
                percentile(result.sorted, 1.0),
                percentile(result.sorted, 0.50),
                percentile(result.sorted, 0.90),
                percentile(result.sorted, 0.95),
                percentile(result.sorted, 0.99),
                percentile(result.sorted, 0.999),
                result.errors, errorRate * 100, errorRate, result.transportErrors,
                result.successBusiness, result.stockEmpty, result.repeatOrder,
                result.rateLimited, result.otherBusiness,
                formatStatusCounts(result.statusCounts),
                result.finished);
        System.out.println("══════════════════════════════════════════════════════════════════");
    }

    /**
     * 分位数计算：与 {@code EndpointMetricsCollector} 完全一致的口径。
     *
     * @param p 0.0 表示最小，1.0 表示最大，-1.0 表示平均
     */
    static double percentile(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        if (p < 0) {
            long sum = 0;
            for (long v : sortedNanos) {
                sum += v;
            }
            return round3(sum / (double) sortedNanos.length / 1_000_000.0);
        }
        if (p == 0.0) {
            return round3(sortedNanos[0] / 1_000_000.0);
        }
        if (p >= 1.0) {
            return round3(sortedNanos[sortedNanos.length - 1] / 1_000_000.0);
        }
        int index = (int) Math.ceil(p * sortedNanos.length) - 1;
        index = Math.max(0, Math.min(sortedNanos.length - 1, index));
        return round3(sortedNanos[index] / 1_000_000.0);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String formatStatusCounts(Map<Integer, LongAdder> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((status, count) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(status).append('=').append(count.sum());
        });
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 解析命令行参数。 */
    static Options parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                map.put(key, value);
            }
        }
        return new Options(
                map.getOrDefault("url", "http://127.0.0.1:8080"),
                map.getOrDefault("scenario", "seckill"),
                Long.parseLong(map.getOrDefault("activity-id", "1")),
                Integer.parseInt(map.getOrDefault("stock", "100")),
                Integer.parseInt(map.getOrDefault("threads", "200")),
                Integer.parseInt(map.getOrDefault("requests", "20000")),
                Integer.parseInt(map.getOrDefault("warmup", "1000")),
                Boolean.parseBoolean(map.getOrDefault("count-4xx-as-error", "false")),
                Long.parseLong(map.getOrDefault("user-base", "2000000")),
                Boolean.parseBoolean(map.getOrDefault("verbose", "false")));
    }

    /** 压测结果。 */
    record Result(int recorded, long elapsedNanos, long[] sorted,
                  Map<Integer, LongAdder> statusCounts, long transportErrors, long errors,
                  long successBusiness, long stockEmpty, long rateLimited, long repeatOrder,
                  long otherBusiness, boolean finished) {
    }
}
