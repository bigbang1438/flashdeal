package com.flashdeal.domain;

import java.time.LocalDateTime;

/**
 * 库存流水。
 *
 * @param id         主键
 * @param activityId 活动 ID
 * @param userId     用户 ID（回补时用于溯源）
 * @param delta      变动量（负数=扣减，正数=回补）
 * @param stockAfter 变动后剩余库存
 * @param reason     原因：DEDUCT_CAS / DEDUCT_MEMORY / ROLLBACK_TIMEOUT / ROLLBACK_PAY_FAIL / PREHEAT
 * @param orderNo    关联订单号
 * @param createdAt  时间
 */
public record StockLog(
        Long id,
        Long activityId,
        Long userId,
        int delta,
        int stockAfter,
        String reason,
        String orderNo,
        LocalDateTime createdAt) {
}
