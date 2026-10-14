package com.flashdeal.cache;

import java.util.function.Function;

/**
 * L1 本地缓存抽象。
 *
 * <p>只暴露必要语义：get / put / invalidate / 统计。实现方需保证线程安全。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface LocalCache<K, V> {

    /** 读取缓存；未命中或已过期返回 null。 */
    V get(K key);

    /** 写入缓存并指定 TTL（毫秒）。 */
    void put(K key, V value, long ttlMillis);

    /** 读取缓存，未命中时用 loader 回源并写入（不加分布式锁，仅做本地防重）。 */
    V getOrLoad(K key, Function<K, V> loader, long ttlMillis);

    /** 主动失效单个 key。 */
    void invalidate(K key);

    /** 清空全部条目。 */
    void clear();

    /** 当前条目数。 */
    int size();

    /** 统计快照。 */
    CacheStats stats();

    /** 缓存名称。 */
    String name();
}
