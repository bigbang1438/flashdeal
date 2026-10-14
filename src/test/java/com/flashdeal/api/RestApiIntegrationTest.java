package com.flashdeal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.support.BaseIntegrationTest;

/**
 * REST API 集成测试：<b>真实 HTTP 请求</b>打真实端口，不走 MockMvc。
 *
 * <p>为什么坚持真实 HTTP：压测、超时、序列化、拦截器顺序、状态码这些
 * 只有在真正的 Servlet 容器里才会暴露问题。MockMvc 会绕过 Tomcat 线程池与连接处理。</p>
 */
@TestPropertySource(properties = {
        "flashdeal.rate-limit.order.capacity=1000000",
        "flashdeal.rate-limit.order.refill-per-second=1000000",
        "flashdeal.rate-limit.order.user-capacity=1000000",
        "flashdeal.rate-limit.order.user-refill-per-second=1000000"
})
class RestApiIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @Test
    @DisplayName("健康检查：应用能响应真实 HTTP 请求并带上 traceId")
    void healthEndpointResponds() {
        ResponseEntity<String> response = rest.getForEntity(baseUrl + "/api/metrics/summary", String.class);

        System.out.printf("""
                ================ 真实 HTTP 请求 ================
                GET /api/metrics/summary -> HTTP %d
                X-Trace-Id = %s
                Content-Type = %s
                ===============================================
                """, response.getStatusCode().value(),
                response.getHeaders().getFirst("X-Trace-Id"),
                response.getHeaders().getContentType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).as("traceId 必须回传").isNotBlank();
    }

    @Test
    @DisplayName("创建商品 -> 创建活动 -> 秒杀下单 -> 支付回调：完整链路走真实 HTTP")
    void fullOrderFlowOverHttp() {
        // 1) 创建商品
        Map<String, Object> productBody = Map.of(
                "name", "HTTP 链路测试商品",
                "description", "通过真实 HTTP 创建",
                "priceCents", 19900,
                "imageUrl", "https://example.com/p.png");
        ResponseEntity<Map<String, Object>> productResponse = postJson("/api/admin/products", productBody);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long productId = longValue(productResponse.getBody(), "data", "id");

        // 2) 创建活动
        Map<String, Object> activityBody = Map.of(
                "productId", productId,
                "title", "HTTP 秒杀活动",
                "seckillPriceCents", 9900,
                "totalStock", 5,
                "startTime", LocalDateTime.now().minusMinutes(1).withNano(0).toString().replace('T', ' '),
                "endTime", LocalDateTime.now().plusMinutes(30).withNano(0).toString().replace('T', ' '));
        ResponseEntity<Map<String, Object>> activityResponse = postJson("/api/admin/activities", activityBody);
        assertThat(activityResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long activityId = longValue(activityResponse.getBody(), "data", "id");

        // 3) 秒杀下单
        Map<String, Object> orderBody = Map.of(
                "activityId", activityId,
                "userId", 900001,
                "quantity", 1,
                "idempotentToken", "http-token-" + System.nanoTime());
        ResponseEntity<Map<String, Object>> orderResponse = postJson("/api/seckill/orders", orderBody);
        assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderNo = stringValue(orderResponse.getBody(), "data", "orderNo");

        // 4) 支付回调
        Map<String, Object> payBody = Map.of(
                "orderNo", orderNo,
                "payChannel", "MOCK",
                "paidAmountCents", 9900);
        ResponseEntity<Map<String, Object>> payResponse = postJson("/api/seckill/pay/callback", payBody);
        assertThat(payResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 5) 查询订单
        ResponseEntity<Map<String, Object>> queryResponse = rest.exchange(
                baseUrl + "/api/seckill/orders/" + orderNo, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });
        String status = stringValue(queryResponse.getBody(), "data", "statusText");

        System.out.printf("""
                ================ 完整下单链路（真实 HTTP） ================
                1. POST /api/admin/products          -> %d  productId=%d
                2. POST /api/admin/activities        -> %d  activityId=%d
                3. POST /api/seckill/orders          -> %d  orderNo=%s
                4. POST /api/seckill/pay/callback    -> %d
                5. GET  /api/seckill/orders/{no}     -> %d  订单状态=%s
                库存: 5 -> %d
                不变量校验: 全链路 HTTP 状态码正确 ? %s
                不变量校验: 支付后状态为已支付 ? %s
                ==========================================================
                """, productResponse.getStatusCode().value(), productId,
                activityResponse.getStatusCode().value(), activityId,
                orderResponse.getStatusCode().value(), orderNo,
                payResponse.getStatusCode().value(),
                queryResponse.getStatusCode().value(), status,
                activityRepository.currentStock(activityId),
                productResponse.getStatusCode() == HttpStatus.OK
                        && orderResponse.getStatusCode() == HttpStatus.CREATED
                        && payResponse.getStatusCode() == HttpStatus.OK,
                "已支付".equals(status));

        assertThat(status).isEqualTo("已支付");
        assertThat(activityRepository.currentStock(activityId)).isEqualTo(4);
    }

    @Test
    @DisplayName("参数校验：非法请求体返回 400 与统一错误体")
    void validationFailureReturns400() {
        Map<String, Object> badBody = Map.of(
                "activityId", 1,
                "userId", 1,
                "quantity", 999); // 超过 @Max(10)

        ResponseEntity<Map<String, Object>> response = postJson("/api/seckill/orders", badBody);

        System.out.printf("""
                ================ 参数校验 ================
                请求 quantity=999（@Max(10)）
                HTTP 状态=%d  业务码=%s  消息=%s
                不变量校验: 返回 400 ? %s
                ==========================================
                """, response.getStatusCode().value(),
                response.getBody() == null ? null : response.getBody().get("code"),
                response.getBody() == null ? null : response.getBody().get("message"),
                response.getStatusCode() == HttpStatus.BAD_REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo(1001);
    }

    @Test
    @DisplayName("业务异常：活动不存在返回 404，库存不足返回 409")
    void businessErrorsMapToCorrectStatus() {
        ResponseEntity<Map<String, Object>> notFound = postJson("/api/seckill/orders", Map.of(
                "activityId", 999999, "userId", 1, "quantity", 1, "idempotentToken", "nf-" + System.nanoTime()));

        long productId = createProduct("库存不足商品", 1000L);
        long activityId = createRunningActivity(productId, 1);
        ResponseEntity<Map<String, Object>> first = postJson("/api/seckill/orders", Map.of(
                "activityId", activityId, "userId", 800001, "quantity", 1,
                "idempotentToken", "s1-" + System.nanoTime()));
        ResponseEntity<Map<String, Object>> second = postJson("/api/seckill/orders", Map.of(
                "activityId", activityId, "userId", 800002, "quantity", 1,
                "idempotentToken", "s2-" + System.nanoTime()));

        System.out.printf("""
                ================ 业务异常状态码映射 ================
                活动不存在       -> HTTP %d  业务码=%s（期望 404/2001）
                第一次下单       -> HTTP %d（期望 201）
                库存不足下单     -> HTTP %d  业务码=%s（期望 409/2004）
                ====================================================
                """, notFound.getStatusCode().value(), notFound.getBody().get("code"),
                first.getStatusCode().value(),
                second.getStatusCode().value(), second.getBody().get("code"));

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().get("code")).isEqualTo(2001);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo(2004);
    }

    @Test
    @DisplayName("分页：返回统一分页结构，size 超限被自动收敛")
    void paginationWorks() {
        long productId = createProduct("分页商品", 1000L);
        for (int i = 0; i < 25; i++) {
            createRunningActivity(productId, 10);
        }

        ResponseEntity<Map<String, Object>> page1 = rest.exchange(
                baseUrl + "/api/admin/activities?page=1&size=10", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });
        ResponseEntity<Map<String, Object>> page3 = rest.exchange(
                baseUrl + "/api/admin/activities?page=3&size=10", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });
        ResponseEntity<Map<String, Object>> huge = rest.exchange(
                baseUrl + "/api/admin/activities?page=1&size=999999", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });

        @SuppressWarnings("unchecked")
        Map<String, Object> data1 = (Map<String, Object>) page1.getBody().get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data3 = (Map<String, Object>) page3.getBody().get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> dataHuge = (Map<String, Object>) huge.getBody().get("data");

        System.out.printf("""
                ================ 分页 ================
                总活动数=%s
                page=1&size=10 -> 返回 %d 条  total=%s  totalPages=%s  hasNext=%s
                page=3&size=10 -> 返回 %d 条  hasNext=%s
                page=1&size=999999 -> 实际 size=%s（被收敛到上限 200）
                不变量校验: 第 1 页 10 条 ? %s
                不变量校验: 第 3 页 5 条且无下一页 ? %s
                =====================================
                """, data1.get("total"),
                ((List<?>) data1.get("list")).size(), data1.get("total"), data1.get("totalPages"),
                data1.get("hasNext"),
                ((List<?>) data3.get("list")).size(), data3.get("hasNext"),
                dataHuge.get("size"),
                ((List<?>) data1.get("list")).size() == 10,
                ((List<?>) data3.get("list")).size() == 5 && Boolean.FALSE.equals(data3.get("hasNext")));

        assertThat(((List<?>) data1.get("list"))).hasSize(10);
        assertThat(data1.get("total")).isEqualTo(25);
        assertThat(((List<?>) data3.get("list"))).hasSize(5);
        assertThat(data3.get("hasNext")).isEqualTo(false);
        assertThat(dataHuge.get("size")).isEqualTo(200);
    }

    @Test
    @DisplayName("指标端点：真实 HTTP 请求后能在 /metrics 中看到该请求的统计")
    void metricsReflectRealRequests() {
        rest.postForEntity(baseUrl + "/api/metrics/reset", null, String.class);        for (int i = 0; i < 30; i++) {
            rest.getForEntity(baseUrl + "/api/metrics/summary", String.class);
        }
        ResponseEntity<Map<String, Object>> metrics = rest.exchange(
                baseUrl + "/api/metrics", HttpMethod.GET, null, new ParameterizedTypeReference<>() { });

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) metrics.getBody().get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregate = (Map<String, Object>) data.get("aggregate");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) data.get("endpoints");

        Map<String, Object> summaryEndpoint = endpoints.stream()
                .filter(e -> "/api/metrics/summary".equals(e.get("endpoint")))
                .findFirst().orElseThrow();

        System.out.printf("""
                ================ 指标端点真实采集 ================
                发起了 30 次 GET /api/metrics/summary
                聚合请求总数=%s
                该端点请求数=%s
                该端点 QPS(1s)=%s
                该端点 P50=%s ms  P95=%s ms  P99=%s ms
                该端点成功率=%s
                端点指标条目数=%d
                不变量校验: 该端点统计 >= 30 ? %s
                不变量校验: P99 >= P50 ? %s
                =================================================
                """, aggregate.get("totalCount"), summaryEndpoint.get("totalCount"),
                summaryEndpoint.get("qps1s"),
                ((Map<?, ?>) summaryEndpoint.get("latency")).get("p50"),
                ((Map<?, ?>) summaryEndpoint.get("latency")).get("p95"),
                ((Map<?, ?>) summaryEndpoint.get("latency")).get("p99"),
                summaryEndpoint.get("successRate"), endpoints.size(),
                ((Number) summaryEndpoint.get("totalCount")).longValue() >= 30,
                ((Number) ((Map<?, ?>) summaryEndpoint.get("latency")).get("p99")).doubleValue()
                        >= ((Number) ((Map<?, ?>) summaryEndpoint.get("latency")).get("p50")).doubleValue());

        assertThat(((Number) summaryEndpoint.get("totalCount")).longValue()).isGreaterThanOrEqualTo(30);
        assertThat(((Number) summaryEndpoint.get("successRate")).doubleValue()).isEqualTo(1.0);
        assertThat(endpoints).isNotEmpty();
    }

    @Test
    @DisplayName("库存看板：返回真实库存与订单数")
    void stockBoardReturnsRealData() {
        long productId = createProduct("看板商品", 1000L);
        long activityId = createRunningActivity(productId, 20);
        postJson("/api/seckill/orders", Map.of(
                "activityId", activityId, "userId", 700001, "quantity", 1,
                "idempotentToken", "board-" + System.nanoTime()));

        ResponseEntity<Map<String, Object>> board = rest.exchange(
                baseUrl + "/api/dashboard/stock-board?limit=10", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) board.getBody().get("data");
        Map<String, Object> item = data.stream()
                .filter(d -> ((Number) d.get("activityId")).longValue() == activityId)
                .findFirst().orElseThrow();

        System.out.printf("""
                ================ 库存看板 ================
                activityId=%s  title=%s
                totalStock=%s  stock=%s  soldCount=%s  soldRatio=%s
                orderCount=%s  paidCount=%s  statusText=%s  stockStrategy=%s
                不变量校验: 看板库存与数据库一致 ? %s
                ==========================================
                """, item.get("activityId"), item.get("title"), item.get("totalStock"),
                item.get("stock"), item.get("soldCount"), item.get("soldRatio"),
                item.get("orderCount"), item.get("paidCount"), item.get("statusText"),
                item.get("stockStrategy"),
                ((Number) item.get("stock")).intValue() == activityRepository.currentStock(activityId));

        assertThat(((Number) item.get("stock")).intValue()).isEqualTo(19);
        assertThat(((Number) item.get("soldCount")).intValue()).isEqualTo(1);
        assertThat(((Number) item.get("totalStock")).intValue()).isEqualTo(20);
    }

    @Test
    @DisplayName("分布式锁实验端点：真实 HTTP 触发并发验证并返回正确性结论")
    void lockVerificationOverHttp() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                baseUrl + "/api/lab/lock/verify?threads=16&loops=200", HttpMethod.POST, null,
                new ParameterizedTypeReference<>() { });

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");

        System.out.printf("""
                ================ 锁验证（HTTP 触发） ================
                实现=%s  线程=%s  每线程循环=%s
                期望计数=%s  实际计数=%s  丢失更新=%s
                正确=%s  耗时=%sms
                不变量校验: 无丢失更新 ? %s
                ====================================================
                """, data.get("lockImpl"), data.get("threads"), data.get("loopsPerThread"),
                data.get("expected"), data.get("actual"), data.get("lostUpdates"),
                data.get("correct"), data.get("elapsedMillis"), Boolean.TRUE.equals(data.get("correct")));

        assertThat(data.get("correct")).isEqualTo(true);
        assertThat(((Number) data.get("lostUpdates")).intValue()).isZero();
        assertThat(((Number) data.get("actual")).longValue())
                .isEqualTo(((Number) data.get("expected")).longValue());
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() { });
    }

    private static long longValue(Map<String, Object> body, String... path) {
        return ((Number) navigate(body, path)).longValue();
    }

    private static String stringValue(Map<String, Object> body, String... path) {
        return String.valueOf(navigate(body, path));
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> body, String... path) {
        Object current = body;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
