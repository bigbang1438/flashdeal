package com.flashdeal.ratelimit;

/**
 * 限流器抽象。
 *
 * <p>所有实现都必须是线程安全的，并且要在「高并发下不误放行」这一点上可被测试验证
 * （见 {@code RateLimiterConcurrencyTest}）。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取一个配额。
     *
     * @param key 限流键（接口名 / 用户ID / IP 等）
     * @return 判定结果
     */
    RateLimitResult tryAcquire(String key);

    /** 算法名，用于 /metrics 展示。 */
    String algorithmName();

    /** 累计放行数。 */
    long allowedCount();

    /** 累计拒绝数。 */
    long rejectedCount();
}
