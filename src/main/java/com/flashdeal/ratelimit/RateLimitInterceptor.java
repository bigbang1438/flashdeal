package com.flashdeal.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.flashdeal.common.ApiResponse;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.metrics.BusinessMetrics;
import com.flashdeal.service.JsonCodec;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 限流拦截器：按「接口 + 用户」双维度限流，超限返回 429。
 *
 * <h2>为什么在拦截器而不是过滤器</h2>
 * <p>拦截器能拿到 {@link HandlerMethod}，可以读到 {@link RateLimited} 注解上的
 * 每接口配额；过滤器只能拿到 URL 字符串，做不了注解驱动的差异化配置。</p>
 *
 * <h2>维度</h2>
 * <ul>
 *   <li><b>接口维度</b>：{@code key = "api:" + 方法签名}，保护下游依赖；</li>
 *   <li><b>用户维度</b>：{@code key = "user:" + userId}，userId 优先从
 *       {@code X-User-Id} 请求头取，其次从请求体里的 userId 字段取（秒杀接口场景）。</li>
 * </ul>
 *
 * <h2>返回 429 而不是 200</h2>
 * <p>压测工具按 HTTP 状态码统计错误率，被限流的请求必须真实体现为 429，
 * 否则「错误率」这个指标会失真。</p>
 */
@Component
@Order(10)
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimiter globalLimiter;
    private final RateLimiter userLimiter;
    private final BusinessMetrics metrics;
    private final long defaultGlobalLimit;
    private final long defaultUserLimit;

    public RateLimitInterceptor(@Qualifier("primaryRateLimiter") RateLimiter globalLimiter,
                                @Qualifier("userTokenBucketLimiter") RateLimiter userLimiter,
                                BusinessMetrics metrics,
                                @Value("${flashdeal.rate-limit.order.capacity:2000}") long defaultGlobalLimit,
                                @Value("${flashdeal.rate-limit.order.user-capacity:50}") long defaultUserLimit) {
        this.globalLimiter = globalLimiter;
        this.userLimiter = userLimiter;
        this.metrics = metrics;
        this.defaultGlobalLimit = defaultGlobalLimit;
        this.defaultUserLimit = defaultUserLimit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RateLimited.class);
        }
        if (annotation == null) {
            return true;
        }

        String apiKey = annotation.key().isEmpty()
                ? "api:" + handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName()
                : "api:" + annotation.key();

        // 接口维度
        long globalLimit = annotation.globalLimit() >= 0 ? annotation.globalLimit() : defaultGlobalLimit;
        if (globalLimit > 0) {
            RateLimitResult result = globalLimiter.tryAcquire(apiKey);
            if (!result.allowed()) {
                metrics.increment(BusinessMetrics.ORDER_RATE_LIMITED);
                writeTooManyRequests(response, result, "接口");
                return false;
            }
            response.setHeader(HEADER_REMAINING, Long.toString(result.remaining()));
        }

        // 用户维度
        long userLimit = annotation.userLimit() >= 0 ? annotation.userLimit() : defaultUserLimit;
        if (userLimit > 0) {
            String userId = resolveUserId(request);
            RateLimitResult result = userLimiter.tryAcquire("user:" + apiKey + ":" + userId);
            if (!result.allowed()) {
                metrics.increment(BusinessMetrics.ORDER_RATE_LIMITED);
                writeTooManyRequests(response, result, "用户");
                return false;
            }
        }
        return true;
    }

    /** 解析用户标识：优先请求头，其次查询参数（压测工具用查询参数更简单）。 */
    private String resolveUserId(HttpServletRequest request) {
        String fromHeader = request.getHeader("X-User-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        String fromParam = request.getParameter("userId");
        if (fromParam != null && !fromParam.isBlank()) {
            return fromParam;
        }
        return request.getRemoteAddr();
    }

    /** 输出 429 与统一响应体，同时带上 Retry-After。 */
    private void writeTooManyRequests(HttpServletResponse response, RateLimitResult result, String dimension)
            throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HEADER_REMAINING, "0");
        response.setHeader(HEADER_RETRY_AFTER, Long.toString(Math.max(1, result.retryAfterMillis() / 1000)));
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.RATE_LIMITED.code(),
                ErrorCode.RATE_LIMITED.defaultMessage() + "（" + dimension + "维度触发，算法="
                        + result.dimension() + "）");
        response.getWriter().write(JsonCodec.encode(body));
        response.getWriter().flush();
    }
}
