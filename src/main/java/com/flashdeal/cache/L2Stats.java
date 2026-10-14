package com.flashdeal.cache;

/**
 * L2 缓存统计。
 *
 * @param backend     后端标识
 * @param hitCount    命中
 * @param missCount   未命中
 * @param hitRate     命中率
 * @param size        条目数
 * @param expiredCount 过期淘汰数
 * @param writeCount  写入数
 */
public record L2Stats(String backend, long hitCount, long missCount, double hitRate,
                      int size, long expiredCount, long writeCount) {
}
