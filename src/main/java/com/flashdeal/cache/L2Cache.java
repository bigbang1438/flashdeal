package com.flashdeal.cache;

import java.time.Duration;

/**
 * L2 缓存抽象（模拟 Redis 这类进程外缓存）。
 *
 * <p>本机没有 Redis，因此提供两个实现：</p>
 * <ul>
 *   <li>{@link EmbeddedL2Cache}：进程内实现（默认启用），语义与 Redis 对齐
 *       （字符串键值、TTL 秒级、无锁读），保证项目零外部依赖可跑；</li>
 *   <li>{@link com.flashdeal.cache.redis.RedisL2Cache}：真实 Jedis 实现，
 *       代码可编译、可单测（用假连接验证命令拼装），只有配置了
 *       {@code flashdeal.cache.l2=redis} 且 Redis 可达时才会被装配。</li>
 * </ul>
 *
 * <p>因此「多级缓存」的架构是真实的，Redis 只是可插拔的 L2 后端之一。</p>
 */
public interface L2Cache {

    /** 读取，未命中返回 null。 */
    String get(String key);

    /** 写入并设置存活时间。 */
    void put(String key, String value, Duration ttl);

    /** 删除。 */
    boolean delete(String key);

    /** 是否存在（未过期）。 */
    boolean exists(String key);

    /** 原子自增，返回自增后的值（秒杀库存预扣等场景用）。 */
    long increment(String key, long delta);

    /** 统计信息。 */
    L2Stats stats();

    /** 后端名称，用于指标端点区分。 */
    String backend();
}
