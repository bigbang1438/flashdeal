package com.flashdeal.domain;

/**
 * 商品。
 *
 * @param id          主键
 * @param name        商品名
 * @param description 描述
 * @param priceCents  原价（分）。金额一律用「分」为单位的 long，避免浮点误差
 * @param imageUrl    图片地址
 * @param status      1 上架 / 0 下架
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record Product(
        Long id,
        String name,
        String description,
        long priceCents,
        String imageUrl,
        int status,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt) {

    public boolean onShelf() {
        return status == 1;
    }

    /** 元为单位的展示价格（仅用于展示，不参与计算）。 */
    public String priceYuan() {
        return String.format("%.2f", priceCents / 100.0);
    }
}
