package com.flashdeal.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * 业务指标计数器（与 HTTP 层指标互补）。
 *
 * <p>HTTP 指标回答「接口快不快」，业务指标回答「生意对不对」：
 * 下单成功多少、库存不足拒绝多少、限流拒绝多少、幂等重放多少。
 * 这些数字直接支撑 README 里「不超卖、不少卖」的结论。</p>
 */
@Component
public class BusinessMetrics {

    public static final String ORDER_SUCCESS = "order.success";
    public static final String ORDER_STOCK_EMPTY = "order.reject.stock_empty";
    public static final String ORDER_REPEAT_USER = "order.reject.repeat_user";
    public static final String ORDER_RATE_LIMITED = "order.reject.rate_limited";
    public static final String ORDER_IDEMPOTENT_REPLAY = "order.idempotent.replay";
    public static final String ORDER_IDEMPOTENT_PROCESSING = "order.idempotent.processing";
    public static final String ORDER_IDEMPOTENT_CONFLICT = "order.idempotent.conflict";
    public static final String ORDER_NOT_STARTED = "order.reject.not_started";
    public static final String ORDER_ENDED = "order.reject.ended";
    public static final String ORDER_PARAM_INVALID = "order.reject.param_invalid";
    public static final String ORDER_INTERNAL_ERROR = "order.error.internal";
    public static final String STOCK_ROLLBACK = "stock.rollback";
    public static final String PAY_SUCCESS = "pay.success";
    public static final String PAY_DUPLICATE = "pay.duplicate";
    public static final String PAY_FAILED = "pay.failed";
    public static final String LOCK_ACQUIRED = "lock.acquired";
    public static final String LOCK_FAILED = "lock.failed";

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void add(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    public long get(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0L : adder.sum();
    }

    /** 所有计数器的快照（按名称排序，保证 /metrics 输出稳定可 diff）。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new java.util.TreeMap<>();
        counters.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    public void reset() {
        counters.clear();
    }
}
