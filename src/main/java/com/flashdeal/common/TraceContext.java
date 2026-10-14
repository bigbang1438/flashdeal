package com.flashdeal.common;

/**
 * 极简 traceId 上下文。
 *
 * <p>不使用 ThreadLocal 之外的任何依赖；由 {@code TraceFilter} 写入与清理，
 * 保证异步线程不会串号（异步任务显式传递 traceId 字符串）。</p>
 */
public final class TraceContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static String current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
