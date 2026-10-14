package com.flashdeal.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 限流器装配。
 *
 * <p>同时装配两个限流器（令牌桶 + 滑动窗口），通过
 * {@code flashdeal.rate-limit.order.algorithm} 决定下单接口用哪个，
 * 另一个仍然可以在 {@code /api/admin/rate-limit/probe} 上做对比演示。</p>
 */
@Configuration
public class RateLimiterConfiguration {

    @Bean("tokenBucketLimiter")
    public RateLimiter tokenBucketLimiter(
            @Value("${flashdeal.rate-limit.order.capacity:2000}") long capacity,
            @Value("${flashdeal.rate-limit.order.refill-per-second:2000}") long refillPerSecond) {
        return new TokenBucketRateLimiter("global", capacity, refillPerSecond);
    }

    @Bean("slidingWindowLimiter")
    public RateLimiter slidingWindowLimiter(
            @Value("${flashdeal.rate-limit.order.capacity:2000}") long limit,
            @Value("${flashdeal.rate-limit.order.window-millis:1000}") long windowMillis) {
        return new SlidingWindowRateLimiter("global", limit, windowMillis);
    }

    @Bean("userTokenBucketLimiter")
    public RateLimiter userTokenBucketLimiter(
            @Value("${flashdeal.rate-limit.order.user-capacity:50}") long capacity,
            @Value("${flashdeal.rate-limit.order.user-refill-per-second:50}") long refillPerSecond) {
        return new TokenBucketRateLimiter("user", capacity, refillPerSecond);
    }

    @Bean("userSlidingWindowLimiter")
    public RateLimiter userSlidingWindowLimiter(
            @Value("${flashdeal.rate-limit.order.user-capacity:50}") long limit,
            @Value("${flashdeal.rate-limit.order.window-millis:1000}") long windowMillis) {
        return new SlidingWindowRateLimiter("user", limit, windowMillis);
    }

    /** 主限流器：由配置决定算法，供拦截器使用。 */
    @Bean
    @Primary
    public RateLimiter primaryRateLimiter(
            @Value("${flashdeal.rate-limit.order.algorithm:token-bucket}") String algorithm,
            @org.springframework.beans.factory.annotation.Qualifier("tokenBucketLimiter") RateLimiter tokenBucket,
            @org.springframework.beans.factory.annotation.Qualifier("slidingWindowLimiter") RateLimiter slidingWindow) {
        return "sliding-window".equalsIgnoreCase(algorithm) ? slidingWindow : tokenBucket;
    }
}
