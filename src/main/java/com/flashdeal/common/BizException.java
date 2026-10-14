package com.flashdeal.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带业务码与 HTTP 状态码，由 {@link GlobalExceptionHandler} 统一转换。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
        this.httpStatus = errorCode.httpStatus();
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
