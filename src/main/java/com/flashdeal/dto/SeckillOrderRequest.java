package com.flashdeal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 秒杀下单请求。
 *
 * @param activityId    活动 ID
 * @param userId        用户 ID（真实系统从登录态取，这里为便于压测显式传入）
 * @param quantity      购买数量
 * @param idempotentToken 幂等令牌；为空时服务端按 userId+activityId 兜底去重
 */
public record SeckillOrderRequest(
        @NotNull(message = "不能为空") Long activityId,
        @NotNull(message = "不能为空") Long userId,
        @Min(value = 1, message = "至少为 1") @Max(value = 10, message = "最多 10") int quantity,
        @Size(max = 96, message = "长度不能超过 96") String idempotentToken) {

    /** 便于测试与压测构造的便捷方法。 */
    public static SeckillOrderRequest of(long activityId, long userId, int quantity) {
        return new SeckillOrderRequest(activityId, userId, quantity, null);
    }

    /** 归一化幂等键：没有显式 token 时用「用户+活动」作为天然幂等键。 */
    public String resolvedIdempotentKey() {
        if (idempotentToken != null && !idempotentToken.isBlank()) {
            return idempotentToken.trim();
        }
        return "auto:u" + userId + ":a" + activityId;
    }
}
