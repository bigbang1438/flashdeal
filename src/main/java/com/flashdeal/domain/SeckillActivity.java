package com.flashdeal.domain;

import java.time.LocalDateTime;

/**
 * 秒杀活动。
 *
 * @param id                主键
 * @param productId         商品 ID
 * @param title             活动标题
 * @param seckillPriceCents 秒杀价（分）
 * @param totalStock        总库存
 * @param stock             剩余库存
 * @param version           乐观锁版本号（方案 A：DB CAS 使用）
 * @param startTime         开始时间
 * @param endTime           结束时间
 * @param status            状态码，见 {@link ActivityStatus}
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record SeckillActivity(
        Long id,
        Long productId,
        String title,
        long seckillPriceCents,
        int totalStock,
        int stock,
        long version,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 已售数量（按总库存 - 剩余库存推算）。 */
    public int soldCount() {
        return totalStock - stock;
    }

    /** 售罄进度（0~1）。 */
    public double soldRatio() {
        return totalStock == 0 ? 0d : Math.round(soldCount() * 10000.0 / totalStock) / 10000.0;
    }
}
