package com.flashdeal.common;

/**
 * 统一响应体。
 *
 * <p>约定：{@code code=0} 表示成功，非 0 表示业务失败；HTTP 状态码同时被设置，
 * 便于压测工具按状态码统计错误率。</p>
 *
 * @param code      业务码，0 成功
 * @param message   提示信息
 * @param data      业务数据
 * @param traceId   请求追踪号（便于日志定位）
 * @param timestamp 服务端时间戳（毫秒）
 */
public record ApiResponse<T>(int code, String message, T data, String traceId, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, TraceContext.current(), System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.current(), System.currentTimeMillis());
    }
}
