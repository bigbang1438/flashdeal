package com.flashdeal.cache;

/**
 * 缓存统计快照。
 *
 * @param name        缓存名称
 * @param hitCount    命中次数
 * @param missCount   未命中次数
 * @param hitRate     命中率（0~1，保留 4 位小数）
 * @param size        当前条目数
 * @param capacity    容量上限
 * @param evictionCount LRU 淘汰次数
 * @param expireCount 因 TTL 过期而失效的次数
 * @param loadCount   回源加载次数（L1 未命中且 L2 也未命中）
 */
public record CacheStats(
        String name,
        long hitCount,
        long missCount,
        double hitRate,
        int size,
        int capacity,
        long evictionCount,
        long expireCount,
        long loadCount) {

    public static double rate(long hit, long miss) {
        long total = hit + miss;
        if (total == 0) {
            return 0d;
        }
        return Math.round(hit * 10000.0 / total) / 10000.0;
    }
}
