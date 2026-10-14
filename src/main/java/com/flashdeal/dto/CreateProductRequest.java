package com.flashdeal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建商品请求。
 *
 * @param name       商品名
 * @param description 描述
 * @param priceCents 原价（分）
 * @param imageUrl   图片地址
 */
public record CreateProductRequest(
        @NotBlank(message = "不能为空") @Size(max = 128, message = "长度不能超过 128") String name,
        @Size(max = 512, message = "长度不能超过 512") String description,
        @Min(value = 1, message = "必须大于 0") long priceCents,
        @Size(max = 256, message = "长度不能超过 256") String imageUrl) {
}
