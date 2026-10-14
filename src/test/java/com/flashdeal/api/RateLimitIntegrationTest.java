package com.flashdeal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.flashdeal.support.BaseIntegrationTest;

/**
 * 限流集成测试：真实 HTTP 请求触发 429。
 *
 * <p>这里刻意把配额调小（容量 20、速率 1/秒），这样 200 个并发请求里
 * 必然有大量请求被拒，从而验证「限流真的生效」而不是「配置写了但没接上」。</p>
 */
@TestPropertySource(properties = {
        "flashdeal.rate-limit.order.capacity=20",
        "flashdeal.rate-limit.order.refill-per-second=1",
        "flashdeal.rate-limit.order.user-capacity=1000000",
        "flashdeal.rate-limit.order.user-refill-per-second=1000000",
        "flashdeal.rate-limit.order.algorithm=token-bucket"
})
class RateLimitIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("接口维度限流：容量 20 时并发 200 请求，必然出现 429 且放行数不超过配额")
    void globalRateLimitReturns429() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("限流测试商品", 1000L);
        long activityId = createRunningActivity(productId, 10_000);

        int requests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        LongAdder ok = new LongAdder();
        LongAdder tooMany = new LongAdder();
        LongAdder conflict = new LongAdder();
        LongAdder other = new LongAdder();
        LongAdder retryAfterSeen = new LongAdder();

        String url = "http://127.0.0.1:" + port + "/api/seckill/orders";
        for (int i = 0; i < requests; i++) {
            final long userId = 500_000L + i;
            pool.submit(() -> {
                try {
                    start.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    // userId 同时作为限流维度的来源（X-User-Id）
                    headers.set("X-User-Id", Long.toString(userId));
                    String body = "{\"activityId\":" + activityId + ",\"userId\":" + userId
                            + ",\"quantity\":1,\"idempotentToken\":\"rl-" + userId + "\"}";
                    ResponseEntity<String> response = rest.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(body, headers), String.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        ok.increment();
                    } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        tooMany.increment();
                        if (response.getHeaders().getFirst("Retry-After") != null) {
                            retryAfterSeen.increment();
                        }
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflict.increment();
                    } else {
                        other.increment();
                    }
                } catch (Exception e) {
                    other.increment();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.currentTimeMillis();
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdownNow();

        System.out.printf("""
                ================ 限流生效验证（真实 HTTP） ================
                配置: 令牌桶 容量=20 速率=1/秒  算法=token-bucket
                并发请求=%d  耗时=%dms  完成=%s
                201 成功=%d
                429 被限流=%d
                409 其它业务冲突=%d
                其它状态=%d
                带 Retry-After 头的 429 数=%d
                不变量校验: 出现过 429 ? %s
                不变量校验: 成功数 <= 容量+补充 ? %s
                不变量校验: 所有 429 都带 Retry-After ? %s
                不变量校验: 请求结果总数 == 发起数 ? %s
                ===========================================================
                """, requests, elapsed, finished,
                ok.sum(), tooMany.sum(), conflict.sum(), other.sum(), retryAfterSeen.sum(),
                tooMany.sum() > 0,
                ok.sum() <= 21,
                retryAfterSeen.sum() == tooMany.sum(),
                ok.sum() + tooMany.sum() + conflict.sum() + other.sum() == requests);

        assertThat(finished).isTrue();
        assertThat(tooMany.sum()).as("必然有请求被限流返回 429").isPositive();
        assertThat(ok.sum()).as("放行数不能超过桶容量 + 执行期间补充量").isLessThanOrEqualTo(21);
        assertThat(retryAfterSeen.sum()).as("429 必须带 Retry-After").isEqualTo(tooMany.sum());
        assertThat(other.sum()).as("不应出现未预期状态码").isZero();
    }

    @Test
    @DisplayName("滑动窗口算法：同样能限流（算法可切换）")
    void slidingWindowAlgorithmAlsoLimits() throws InterruptedException {
        cleanDatabase();
        long productId = createProduct("滑动窗口测试商品", 1000L);
        long activityId = createRunningActivity(productId, 10_000);

        // 直接用限流器验证算法（HTTP 层已在上个用例验证），避免重复启动容器配置
        com.flashdeal.ratelimit.SlidingWindowRateLimiter limiter =
                new com.flashdeal.ratelimit.SlidingWindowRateLimiter("w", 20, 60_000);

        int requests = 200;
        int allowed = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.tryAcquire("api:seckill-order").allowed()) {
                allowed++;
            }
        }

        System.out.printf("""
                ================ 滑动窗口限流算法 ================
                配置: 滑动窗口 配额=20 / 窗口=60s
                请求=%d  放行=%d  拒绝=%d
                不变量校验: 放行数 == 配额 20 ? %s
                =================================================
                """, requests, allowed, requests - allowed, allowed == 20);

        assertThat(allowed).as("滑动窗口应精确放行配额数量").isEqualTo(20);
    }

    @Test
    @DisplayName("用户维度限流：单用户高频请求被单独拦截")
    void perUserRateLimit() {
        // 用户维度限流器独立验证（HTTP 层配置为不限用户，这里直接测算法行为）
        com.flashdeal.ratelimit.TokenBucketRateLimiter userLimiter =
                new com.flashdeal.ratelimit.TokenBucketRateLimiter("user", 5, 1);

        int user1Allowed = 0;
        int user2Allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (userLimiter.tryAcquire("user:api:seckill-order:1001").allowed()) {
                user1Allowed++;
            }
        }
        for (int i = 0; i < 50; i++) {
            if (userLimiter.tryAcquire("user:api:seckill-order:1002").allowed()) {
                user2Allowed++;
            }
        }

        System.out.printf("""
                ================ 用户维度限流 ================
                单用户配额=5
                user 1001 放行=%d   user 1002 放行=%d
                跟踪的 key 数=%d
                不变量校验: 每个用户独立 5 个配额 ? %s
                =============================================
                """, user1Allowed, user2Allowed, userLimiter.trackedKeys(),
                user1Allowed == 5 && user2Allowed == 5);

        assertThat(user1Allowed).isEqualTo(5);
        assertThat(user2Allowed).isEqualTo(5);
    }
}
