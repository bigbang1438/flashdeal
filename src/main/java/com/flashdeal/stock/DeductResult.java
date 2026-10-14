package com.flashdeal.stock;

/**
 * 库存扣减结果。
 *
 * @param success    是否扣减成功
 * @param reason     结果原因（用于指标与日志）
 * @param stockAfter 扣减后剩余库存（未知时为 -1）
 * @param retries    CAS 重试次数（方案 A 专用，可观测冲突强度）
 */
public record DeductResult(boolean success, String reason, int stockAfter, int retries) {

    public static final String OK = "OK";
    public static final String STOCK_NOT_ENOUGH = "STOCK_NOT_ENOUGH";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String ACTIVITY_NOT_FOUND = "ACTIVITY_NOT_FOUND";

    public static DeductResult ok(int stockAfter, int retries) {
        return new DeductResult(true, OK, stockAfter, retries);
    }

    public static DeductResult fail(String reason, int retries) {
        return new DeductResult(false, reason, -1, retries);
    }
}
