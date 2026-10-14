package com.flashdeal.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码表。
 *
 * <p>编码规则：1xxx 通用 / 2xxx 库存 / 3xxx 限流与幂等 / 4xxx 消息队列。</p>
 */
public enum ErrorCode {

    // ---- 1xxx 通用 ----
    PARAM_INVALID(1001, "参数校验失败", HttpStatus.BAD_REQUEST),
    NOT_FOUND(1002, "资源不存在", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(1500, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),

    // ---- 2xxx 秒杀与库存 ----
    ACTIVITY_NOT_FOUND(2001, "秒杀活动不存在", HttpStatus.NOT_FOUND),
    ACTIVITY_NOT_STARTED(2002, "秒杀活动尚未开始", HttpStatus.CONFLICT),
    ACTIVITY_ENDED(2003, "秒杀活动已结束", HttpStatus.CONFLICT),
    STOCK_NOT_ENOUGH(2004, "库存不足", HttpStatus.CONFLICT),
    REPEAT_ORDER(2005, "您已参与过该秒杀活动", HttpStatus.CONFLICT),
    ORDER_NOT_FOUND(2006, "订单不存在", HttpStatus.NOT_FOUND),
    ORDER_STATUS_ILLEGAL(2007, "订单状态不允许该操作", HttpStatus.CONFLICT),
    STOCK_DEDUCT_CONFLICT(2008, "库存扣减冲突，请重试", HttpStatus.CONFLICT),

    // ---- 3xxx 限流与幂等 ----
    RATE_LIMITED(3001, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    IDEMPOTENT_CONFLICT(3002, "幂等令牌已被不同请求占用", HttpStatus.CONFLICT),
    IDEMPOTENT_PROCESSING(3003, "相同请求正在处理中，请稍后重试", HttpStatus.CONFLICT),
    IDEMPOTENT_TOKEN_INVALID(3004, "幂等令牌无效", HttpStatus.BAD_REQUEST),

    // ---- 4xxx 消息队列 ----
    MQ_ENQUEUE_FAILED(4001, "消息入队失败", HttpStatus.SERVICE_UNAVAILABLE),
    MQ_QUEUE_FULL(4002, "队列已满，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
