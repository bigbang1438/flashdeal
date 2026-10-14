package com.flashdeal.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * L2 缓存的嵌入式实现（默认启用）。
 *
 * <p>语义对齐 Redis 字符串模型：key 为 String、value 为 String、TTL 为秒级粒度、
 * 读路径无锁。这样 {@link CacheFacade} 的 L1→L2→DB 三级回源逻辑在
 * 「嵌入式」与「真 Redis」两种后端下完全一致，切换只改配置。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} + 每个条目自带过期时间戳；
 * 过期采用惰性删除 + 写入时顺带清理。</p>
 */
@Component
@ConditionalOnProperty(name = "flashdeal.cache.l2", havingValue = "embedded", matchIfMissing = true)
public class EmbeddedL2Cache implements L2Cache {

    private static final int CLEANUP_THRESHOLD = 4096;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder expiredCount = new LongAdder();
    private final LongAdder writeCount = new LongAdder();
    private final AtomicLong opsSinceCleanup = new AtomicLong();

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            missCount.increment();
            return null;
        }
        if (entry.expireAtMillis > 0 && System.currentTimeMillis() > entry.expireAtMillis) {
            if (store.remove(key, entry)) {
                expiredCount.increment();
            }
            missCount.increment();
            return null;
        }
        hitCount.increment();
        return entry.value;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        long expireAt = ttl == null || ttl.isZero() || ttl.isNegative()
                ? 0L
                : System.currentTimeMillis() + ttl.toMillis();
        store.put(key, new Entry(value, expireAt));
        writeCount.increment();
        maybeCleanup();
    }

    @Override
    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    @Override
    public boolean exists(String key) {
        return get(key) != null;
    }

    @Override
    public long increment(String key, long delta) {
        // compute 保证同一 key 的读改写原子性
        Entry updated = store.compute(key, (k, old) -> {
            long base = 0L;
            long expireAt = 0L;
            if (old != null && (old.expireAtMillis == 0 || System.currentTimeMillis() <= old.expireAtMillis)) {
                base = Long.parseLong(old.value);
                expireAt = old.expireAtMillis;
            }
            return new Entry(Long.toString(base + delta), expireAt);
        });
        writeCount.increment();
        return Long.parseLong(updated.value);
    }

    @Override
    public L2Stats stats() {
        long hit = hitCount.sum();
        long miss = missCount.sum();
        return new L2Stats("embedded-l2", hit, miss, CacheStats.rate(hit, miss),
                store.size(), expiredCount.sum(), writeCount.sum());
    }

    @Override
    public String backend() {
        return "embedded-l2";
    }

    /** 防止长期运行后过期条目堆积：每 CLEANUP_THRESHOLD 次写操作清理一次。 */
    private void maybeCleanup() {
        if (opsSinceCleanup.incrementAndGet() < CLEANUP_THRESHOLD) {
            return;
        }
        opsSinceCleanup.set(0);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            Entry v = e.getValue();
            if (v.expireAtMillis > 0 && now > v.expireAtMillis && store.remove(e.getKey(), v)) {
                expiredCount.increment();
            }
        }
    }

    /** 缓存条目。 */
    private record Entry(String value, long expireAtMillis) {
    }
}
