package com.flashdeal.service;

/**
 * 订单持久化指令（异步削峰路径的队列消息）。
 *
 * @param orderNo      订单号
 * @param userId       用户 ID
 * @param activityId   活动 ID
 * @param productId    商品 ID
 * @param quantity     数量
 * @param amountCents  金额（分）
 * @param idempotentKey 幂等键
 * @param expireAt     支付超时时间（ISO 字符串，便于序列化）
 * @param enqueueAt    入队时间戳（毫秒），用于统计端到端排队耗时
 */
public record OrderCommand(
        String orderNo,
        long userId,
        long activityId,
        long productId,
        int quantity,
        long amountCents,
        String idempotentKey,
        String expireAt,
        long enqueueAt) {
}
