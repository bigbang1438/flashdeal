package com.flashdeal.domain;

import java.time.LocalDateTime;

/**
 * 秒杀订单。
 *
 * @param id            主键
 * @param orderNo       订单号（业务唯一）
 * @param userId        用户 ID
 * @param activityId    活动 ID
 * @param productId     商品 ID
 * @param quantity      数量
 * @param amountCents   订单金额（分）
 * @param status        状态码，见 {@link OrderStatus}
 * @param idempotentKey 幂等键（原始幂等 token）
 * @param createPath    下单路径：DB_CAS / MEMORY_PREDUCT / MEMORY_SYNC_FALLBACK
 * @param createdAt     创建时间
 * @param paidAt        支付时间
 * @param expireAt      支付超时时间
 * @param updatedAt     更新时间
 */
public record SeckillOrder(
        Long id,
        String orderNo,
        Long userId,
        Long activityId,
        Long productId,
        int quantity,
        long amountCents,
        int status,
        String idempotentKey,
        String createPath,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime expireAt,
        LocalDateTime updatedAt) {

    public OrderStatus orderStatus() {
        return OrderStatus.of(status);
    }

    public boolean pendingPay() {
        return status == OrderStatus.PENDING_PAY.code();
    }
}
