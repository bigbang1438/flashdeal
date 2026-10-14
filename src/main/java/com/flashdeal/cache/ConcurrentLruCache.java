package com.flashdeal.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * 自研 L1 本地缓存实现：分段锁 + LRU 淘汰 + TTL + 命中率统计。
 *
 * <h2>为什么不用 Caffeine / Guava</h2>
 * <p>题目要求缓存自研，且本机不能引入额外中间件；同时自研能精确控制
 * 「过期判定时机」与「命中率统计口径」，让 README 里的命中率数字可解释。</p>
 *
 * <h2>数据结构</h2>
 * <ul>
 *   <li>哈希层：{@link ConcurrentHashMap}，读路径无锁；</li>
 *   <li>LRU 层：{@link ConcurrentLinkedLru}（自己用双向链表 + ReentrantLock 实现，
 *       非 LinkedHashMap，避免全局锁 + 支持 O(1) 移动）；</li>
 *   <li>TTL 层：每个 Entry 自带 {@code expireAtNanos}，惰性删除 + 后台清理线程兜底。</li>
 * </ul>
 *
 * <h2>统计口径（重要，README 的数字依赖它）</h2>
 * <ul>
 *   <li>hit：{@code get()} 命中且未过期；</li>
 *   <li>miss：{@code get()} 未命中，或命中但已过期（过期按 miss 计，并计入 expireCount）；</li>
 *   <li>hitRate = hit / (hit + miss)，四舍五入到 4 位小数。</li>
 * </ul>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class ConcurrentLruCache<K, V> implements LocalCache<K, V> {

    private final String name;
    private final int capacity;
    private final long defaultTtlMillis;

    private final ConcurrentHashMap<K, Entry<K, V>> map;
    private final ConcurrentLinkedLru<K> lru;

    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();
    private final LongAdder expireCount = new LongAdder();
    private final LongAdder loadCount = new LongAdder();

    public ConcurrentLruCache(String name, int capacity, long defaultTtlMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须为正数");
        }
        this.name = name;
        this.capacity = capacity;
        this.defaultTtlMillis = defaultTtlMillis;
        this.map = new ConcurrentHashMap<>(Math.min(capacity, 1 << 16));
        this.lru = new ConcurrentLinkedLru<>();
    }

    @Override
    public V get(K key) {
        Entry<K, V> entry = map.get(key);
        if (entry == null) {
            missCount.increment();
            return null;
        }
        if (entry.isExpired()) {
            // 惰性删除：过期即视为未命中
            if (map.remove(key, entry)) {
                lru.remove(key);
                expireCount.increment();
            }
            missCount.increment();
            return null;
        }
        lru.touch(key);
        hitCount.increment();
        return entry.value;
    }

    @Override
    public void put(K key, V value, long ttlMillis) {
        if (key == null || value == null) {
            return;
        }
        long ttl = ttlMillis > 0 ? ttlMillis : defaultTtlMillis;
        Entry<K, V> fresh = new Entry<>(key, value, System.nanoTime() + ttl * 1_000_000L);
        Entry<K, V> old = map.put(key, fresh);
        if (old == null) {
            lru.add(key);
            evictIfNeeded();
        } else {
            lru.touch(key);
        }
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader, long ttlMillis) {
        V cached = get(key);
        if (cached != null) {
            return cached;
        }
        // 本机场景下不做 per-key 单飞，避免引入额外的锁竞争；
        // 秒杀主链路的热点 key 由「库存预热」提前写入，回源只是兜底。
        V loaded = loader.apply(key);
        if (loaded != null) {
            loadCount.increment();
            put(key, loaded, ttlMillis);
        }
        return loaded;
    }

    @Override
    public void invalidate(K key) {
        Entry<K, V> removed = map.remove(key);
        if (removed != null) {
            lru.remove(key);
        }
    }

    @Override
    public void clear() {
        map.clear();
        lru.clear();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public CacheStats stats() {
        long hit = hitCount.sum();
        long miss = missCount.sum();
        return new CacheStats(name, hit, miss, CacheStats.rate(hit, miss),
                map.size(), capacity, evictionCount.sum(), expireCount.sum(), loadCount.sum());
    }

    @Override
    public String name() {
        return name;
    }

    /** 淘汰最久未使用的条目，直到条目数不超过容量。 */
    private void evictIfNeeded() {
        while (map.size() > capacity) {
            K victim = lru.removeEldest();
            if (victim == null) {
                return;
            }
            map.remove(victim);
            evictionCount.increment();
        }
    }

    /**
     * 清理所有已过期条目，由后台定时任务调用（惰性删除的兜底）。
     *
     * @return 被清理的条目数
     */
    public int purgeExpired() {
        int removed = 0;
        for (Entry<K, V> entry : map.values()) {
            if (entry.isExpired() && map.remove(entry.key, entry)) {
                lru.remove(entry.key);
                expireCount.increment();
                removed++;
            }
        }
        return removed;
    }

    /** 缓存条目：值 + 绝对过期时间（纳秒）。 */
    private static final class Entry<K, V> {
        final K key;
        final V value;
        final long expireAtNanos;

        Entry(K key, V value, long expireAtNanos) {
            this.key = key;
            this.value = value;
            this.expireAtNanos = expireAtNanos;
        }

        boolean isExpired() {
            return System.nanoTime() - expireAtNanos > 0;
        }
    }
}
