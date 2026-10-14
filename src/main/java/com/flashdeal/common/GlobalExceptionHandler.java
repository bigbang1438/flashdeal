package com.flashdeal.common;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理：把所有异常收敛成统一响应体，并保留正确的 HTTP 状态码。
 *
 * <p>压测工具按 HTTP 状态码统计错误率，因此 429 / 409 / 400 必须真实回传，
 * 不能统一吞成 200。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：直接按错误码映射状态码。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.httpStatus())
                .body(ApiResponse.fail(ex.code(), ex.getMessage()));
    }

    /** @Valid 校验失败（请求体）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), detail));
    }

    /** 表单/查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        String detail = ex.getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), detail));
    }

    /** 方法级 @Validated 校验失败（如 @RequestParam @Min）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(this::formatViolation)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), detail));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleParam(Exception ex) {
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), ex.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND.code(), "接口不存在: " + ex.getRequestURL()));
    }

    /** 兜底：任何未预期异常都记录堆栈，但对外只暴露统一错误码。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("未处理异常 traceId={}", TraceContext.current(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private String formatViolation(ConstraintViolation<?> violation) {
        List<String> path = violation.getPropertyPath().toString().lines().toList();
        return String.join("", path) + " " + violation.getMessage();
    }
}
