package com.flashdeal.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 创建秒杀活动请求。
 *
 * @param productId         商品 ID
 * @param title             活动标题
 * @param seckillPriceCents 秒杀价（分）
 * @param totalStock        总库存
 * @param startTime         开始时间
 * @param endTime           结束时间
 */
public record CreateActivityRequest(
        @NotNull(message = "不能为空") Long productId,
        @NotBlank(message = "不能为空") @Size(max = 128, message = "长度不能超过 128") String title,
        @Min(value = 1, message = "必须大于 0") long seckillPriceCents,
        @Min(value = 1, message = "至少为 1") @jakarta.validation.constraints.Max(value = 1_000_000, message = "最多 1000000")
        int totalStock,
        @NotNull(message = "不能为空")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,
        @NotNull(message = "不能为空")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime) {

    /** 业务校验：开始时间必须早于结束时间。 */
    public boolean timeRangeValid() {
        return startTime != null && endTime != null && startTime.isBefore(endTime);
    }
}
