package com.flashdeal.service;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.cache.ConcurrentLruCache;
import com.flashdeal.cache.L2Cache;
import com.flashdeal.cache.L2Stats;
import com.flashdeal.cache.LocalCache;

/**
 * 多级缓存门面：L1（本地）→ L2（进程外）→ DB（回源）。
 *
 * <h2>读路径</h2>
 * <pre>
 * get(key)
 *   ├─ L1 命中   → 直接返回（纳秒级，无序列化）
 *   ├─ L1 未命中 → 查 L2
 *   │     ├─ L2 命中   → 反序列化 + 回填 L1，返回
 *   │     └─ L2 未命中 → 回源 DB，回填 L2 + L1，返回
 * </pre>
 *
 * <h2>命中率口径（README 引用的数字来自这里）</h2>
 * <ul>
 *   <li>{@code L1 命中率} = L1 命中 / (L1 命中 + L1 未命中)；</li>
 *   <li>{@code 综合命中率} = (L1 命中 + L2 命中) / (L1 命中 + L1 未命中)。
 *       因为 L2 只在 L1 未命中时被访问，分母用 L1 的总访问数即可。</li>
 * </ul>
 *
 * <h2>一致性策略</h2>
 * <p>写路径采用 Cache-Aside 的「先更新 DB，再删缓存」：删除失败最多导致一次脏读，
 * 而 TTL 会在 {@code ttlMillis} 内兜底修正。库存/订单这类强一致数据不缓存
 * 「剩余库存」的精确值用于扣减，只缓存活动元数据。</p>
 */
@Service
public class CacheFacade {

    private final LocalCache<String, Object> l1;
    private final L2Cache l2;
    private final String namespace;
    private final long defaultTtlMillis;

    private final java.util.concurrent.atomic.LongAdder dbLoadCount =
            new java.util.concurrent.atomic.LongAdder();

    public CacheFacade(
            L2Cache l2,
            @Value("${flashdeal.cache.product.max-size:10000}") int maxSize,
            @Value("${flashdeal.cache.product.ttl-millis:60000}") long ttlMillis,
            @Value("${flashdeal.cache.namespace:flashdeal:}") String namespace) {
        this.l2 = l2;
        this.namespace = namespace;
        this.defaultTtlMillis = ttlMillis;
        this.l1 = new ConcurrentLruCache<>("L1-local-cache", maxSize, ttlMillis);
    }

    /** 三级回源读取。 */
    public <T> T get(String key, Class<T> type, Supplier<T> loader, Duration ttl) {
        Object cached = l1.get(key);
        if (cached != null) {
            return type.cast(cached);
        }
        String l2Value = l2.get(namespace + key);
        if (l2Value != null) {
            T value = JsonCodec.decode(l2Value, type);
            if (value != null) {
                l1.put(key, value, ttl.toMillis());
                return value;
            }
        }
        T loaded = loader.get();
        if (loaded != null) {
            dbLoadCount.increment();
            String json = JsonCodec.encode(loaded);
            if (json != null) {
                l2.put(namespace + key, json, ttl);
            }
            l1.put(key, loaded, ttl.toMillis());
        }
        return loaded;
    }

    /** 默认 TTL 版本。 */
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        return get(key, type, loader, Duration.ofMillis(defaultTtlMillis));
    }

    /**
     * 带 Optional 语义的读取：能区分「对象不存在」与「缓存未命中」。
     *
     * <p>「不存在」的结果也会被缓存（存成 JSON 的 null 哨兵），防止缓存穿透。</p>
     */
    public <T> Optional<T> getOptional(String key, Class<T> type, Function<String, Optional<T>> loader, Duration ttl) {
        Object cached = l1.get(key);
        if (cached != null) {
            return cached == NullSentinel.INSTANCE ? Optional.empty() : Optional.of(type.cast(cached));
        }
        String l2Value = l2.get(namespace + key);
        if (l2Value != null) {
            if (NullSentinel.JSON.equals(l2Value)) {
                l1.put(key, NullSentinel.INSTANCE, ttl.toMillis());
                return Optional.empty();
            }
            T value = JsonCodec.decode(l2Value, type);
            if (value != null) {
                l1.put(key, value, ttl.toMillis());
                return Optional.of(value);
            }
        }
        Optional<T> loaded = loader.apply(key);
        if (loaded.isPresent()) {
            dbLoadCount.increment();
            String json = JsonCodec.encode(loaded.get());
            if (json != null) {
                l2.put(namespace + key, json, ttl);
            }
            l1.put(key, loaded.get(), ttl.toMillis());
        } else {
            // 空值缓存：TTL 取短一些，避免数据刚创建却一直读不到
            l2.put(namespace + key, NullSentinel.JSON, Duration.ofSeconds(Math.min(30, ttl.toSeconds())));
            l1.put(key, NullSentinel.INSTANCE, Math.min(30_000, ttl.toMillis()));
        }
        return loaded;
    }

    /** 直接写入两级缓存（库存预热等场景）。 */
    public void put(String key, Object value, Duration ttl) {
        String json = JsonCodec.encode(value);
        if (json != null) {
            l2.put(namespace + key, json, ttl);
        }
        l1.put(key, value, ttl.toMillis());
    }

    /** 使缓存失效（写路径调用）。 */
    public void invalidate(String key) {
        l1.invalidate(key);
        l2.delete(namespace + key);
    }

    public void clear() {
        l1.clear();
    }

    public CacheStats l1Stats() {
        return l1.stats();
    }

    public L2Stats l2Stats() {
        return l2.stats();
    }

    /** 综合命中率 = (L1 命中 + L2 命中) / (L1 命中 + L1 未命中)。 */
    public double overallHitRate() {
        CacheStats s = l1.stats();
        long total = s.hitCount() + s.missCount();
        if (total == 0) {
            return 0d;
        }
        long l2Hits = l2.stats().hitCount();
        return Math.round((s.hitCount() + l2Hits) * 10000.0 / total) / 10000.0;
    }

    /** 真正的回源（DB）次数。 */
    public long dbLoadCount() {
        return dbLoadCount.sum();
    }

    public int l1Size() {
        return l1.size();
    }

    public LocalCache<String, Object> l1() {
        return l1;
    }

    public long defaultTtlMillis() {
        return defaultTtlMillis;
    }

    /** 空值哨兵：缓存「不存在」这一事实，防止缓存穿透。 */
    public enum NullSentinel {
        INSTANCE;

        public static final String JSON = "\"__NULL__\"";
    }
}
