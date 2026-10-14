package com.flashdeal.stock;

/**
 * 库存变更指令（内存预扣方案中，入队等待异步落库的消息体）。
 *
 * @param activityId 活动 ID
 * @param userId     用户 ID
 * @param delta      变动量（负数扣减，正数回补）
 * @param reason     原因（DEDUCT_MEMORY / ROLLBACK_TIMEOUT ...）
 * @param orderNo    订单号
 * @param seq        单调递增序号，作为队列消息 key（保证失败集合可定位）
 */
public record StockCommand(long activityId, long userId, int delta, String reason, String orderNo, long seq) {
}
