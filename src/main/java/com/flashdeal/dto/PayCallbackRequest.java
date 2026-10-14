package com.flashdeal.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 支付回调请求。
 *
 * @param orderNo     订单号
 * @param payChannel  支付渠道（模拟）
 * @param paidAmountCents 实付金额（分），用于校验金额一致
 */
public record PayCallbackRequest(
        @NotNull(message = "不能为空") String orderNo,
        String payChannel,
        long paidAmountCents) {
}
