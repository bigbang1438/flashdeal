package com.flashdeal.dto;

/**
 * 库存看板条目。
 *
 * @param activityId    活动 ID
 * @param title         活动标题
 * @param totalStock    总库存
 * @param stock         剩余库存（数据库真值）
 * @param memoryStock   内存预扣余额（仅 memory 策略下有值，否则等于数据库真值）
 * @param soldCount     已售
 * @param soldRatio     售罄进度
 * @param orderCount    订单数（全部状态）
 * @param paidCount     已支付订单数
 * @param status        活动状态码
 * @param statusText    状态文本
 * @param stockStrategy 当前库存扣减策略
 */
public record StockBoardItem(
        long activityId,
        String title,
        int totalStock,
        int stock,
        int memoryStock,
        int soldCount,
        double soldRatio,
        long orderCount,
        long paidCount,
        int status,
        String statusText,
        String stockStrategy) {
}
