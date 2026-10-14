package com.flashdeal.ratelimit;

/**
 * 单次限流判定结果。
 *
 * @param allowed           是否放行
 * @param limit             配额上限
 * @param remaining         剩余配额
 * @param retryAfterMillis  建议重试等待时间（毫秒），放行时为 0
 * @param dimension         限流维度（GLOBAL / USER）
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterMillis, String dimension) {

    public static RateLimitResult allow(long limit, long remaining, String dimension) {
        return new RateLimitResult(true, limit, remaining, 0L, dimension);
    }

    public static RateLimitResult reject(long limit, long retryAfterMillis, String dimension) {
        return new RateLimitResult(false, limit, 0L, retryAfterMillis, dimension);
    }
}
