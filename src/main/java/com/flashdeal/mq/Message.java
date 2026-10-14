package com.flashdeal.mq;

/**
 * 队列消息。
 *
 * @param key       业务键（如 orderNo），用于消费幂等与死信检索
 * @param payload   消息体
 * @param attempt   已投递次数（从 1 开始）
 * @param createdAt 入队时间戳（毫秒）
 * @param <T>       载荷类型
 */
public record Message<T>(String key, T payload, int attempt, long createdAt) {

    public static <T> Message<T> of(String key, T payload) {
        return new Message<>(key, payload, 1, System.currentTimeMillis());
    }

    /** 重试时递增投递次数。 */
    public Message<T> nextAttempt() {
        return new Message<>(key, payload, attempt + 1, createdAt);
    }

    /** 在队列中的存活时间（毫秒），用于削峰效果统计。 */
    public long latencyMillis() {
        return System.currentTimeMillis() - createdAt;
    }
}
