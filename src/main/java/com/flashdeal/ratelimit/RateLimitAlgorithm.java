package com.flashdeal.ratelimit;

/**
 * 限流算法枚举。
 */
public enum RateLimitAlgorithm {
    /** 令牌桶：允许突发，长期速率受限。 */
    TOKEN_BUCKET,
    /** 滑动窗口：严格限制单位时间内的请求数，无突发。 */
    SLIDING_WINDOW
}
