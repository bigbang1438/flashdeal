package com.flashdeal.common;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * traceId 过滤器：为每个请求生成（或透传）一个 traceId，写入响应头与日志 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private static final AtomicLong SEQ = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = Long.toHexString(System.currentTimeMillis()) + "-"
                    + Long.toHexString(SEQ.incrementAndGet()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
        }
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            MDC.remove("traceId");
        }
    }
}
