package com.flashdeal.metrics;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 指标拦截器：给每个进入 Controller 的请求打点。
 *
 * <p>端点名做归一化（把路径里的数字替换成 {@code {id}}），否则每个订单 ID 都会
 * 变成一个独立的指标 key，内存会被打爆、分位统计也会失真。</p>
 */
@Component
public class MetricsInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "flashdeal.metrics.startNanos";

    private final MetricsRegistry registry;

    public MetricsInterceptor(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        if (start == null) {
            return;
        }
        long latency = System.nanoTime() - (Long) start;
        String endpoint = normalize(request.getRequestURI());
        registry.record(endpoint, latency, response.getStatus());
    }

    /**
     * 路径归一化：{@code /api/seckill/orders/12345} → {@code /api/seckill/orders/{id}}。
     *
     * <p>同时把 {@code /metrics} 自身排除在统计外，避免观测行为污染被测指标。</p>
     */
    static String normalize(String uri) {
        if (uri == null || uri.isEmpty()) {
            return MetricsRegistry.UNKNOWN_ENDPOINT;
        }
        StringBuilder sb = new StringBuilder(uri.length());
        for (String segment : uri.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/');
            sb.append(isNumeric(segment) ? "{id}" : segment);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
