package com.flashdeal.dto;

import com.flashdeal.domain.SeckillOrder;

/**
 * 秒杀下单响应。
 *
 * @param orderNo      订单号
 * @param userId       用户 ID
 * @param activityId   活动 ID
 * @param productId    商品 ID
 * @param quantity     数量
 * @param amountCents  金额（分）
 * @param status       订单状态码
 * @param statusText   状态中文描述
 * @param createPath   下单路径（DB_CAS / MEMORY_PREDUCT）
 * @param idempotent   是否为幂等命中（true 表示这次没有真实下单，直接返回已有订单）
 * @param expireAt     支付超时时间（字符串）
 */
public record SeckillOrderResponse(
        String orderNo,
        long userId,
        long activityId,
        long productId,
        int quantity,
        long amountCents,
        int status,
        String statusText,
        String createPath,
        boolean idempotent,
        String expireAt) {

    public static SeckillOrderResponse of(SeckillOrder order, boolean idempotent) {
        return new SeckillOrderResponse(
                order.orderNo(),
                order.userId(),
                order.activityId(),
                order.productId(),
                order.quantity(),
                order.amountCents(),
                order.status(),
                statusText(order.status()),
                order.createPath(),
                idempotent,
                order.expireAt() == null ? null : order.expireAt().toString());
    }

    private static String statusText(int status) {
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            default -> "已关闭";
        };
    }
}
