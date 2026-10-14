package com.flashdeal.domain;

/**
 * 订单状态。
 */
public enum OrderStatus {
    /** 待支付 */
    PENDING_PAY(0),
    /** 已支付 */
    PAID(1),
    /** 已取消（超时未支付，库存已回补） */
    CANCELLED(2),
    /** 已关闭（人工） */
    CLOSED(3);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static OrderStatus of(int code) {
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知订单状态: " + code);
    }
}
