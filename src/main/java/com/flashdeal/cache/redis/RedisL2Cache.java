package com.flashdeal.cache.redis;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

import com.flashdeal.cache.CacheStats;
import com.flashdeal.cache.L2Cache;
import com.flashdeal.cache.L2Stats;

import redis.clients.jedis.JedisPooled;

/**
 * L2 缓存的 Redis 适配实现（生产用，本机未连真 Redis）。
 *
 * <p>与 {@link com.flashdeal.cache.EmbeddedL2Cache} 实现同一 {@link L2Cache} 接口，
 * 因此上层 {@code CacheFacade} 的 L1→L2→DB 回源逻辑零改动。</p>
 *
 * <p>命令映射：get→GET，put→SET PX，delete→DEL，exists→EXISTS，
 * increment→INCRBY。命中率在客户端侧统计（Redis INFO 的 keyspace_hits
 * 无法按业务缓存维度拆分）。</p>
 */
public class RedisL2Cache implements L2Cache {

    private final JedisPooled jedis;
    private final String namespace;

    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder writeCount = new LongAdder();

    public RedisL2Cache(String url, String namespace) {
        this.jedis = new JedisPooled(url);
        this.namespace = namespace;
    }

    @Override
    public String get(String key) {
        String value = jedis.get(ns(key));
        if (value == null) {
            missCount.increment();
        } else {
            hitCount.increment();
        }
        return value;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        jedis.set(ns(key), value, redis.clients.jedis.params.SetParams.setParams().px(ttl.toMillis()));
        writeCount.increment();
    }

    @Override
    public boolean delete(String key) {
        return jedis.del(ns(key)) > 0;
    }

    @Override
    public boolean exists(String key) {
        return jedis.exists(ns(key));
    }

    @Override
    public long increment(String key, long delta) {
        return jedis.incrBy(ns(key), delta);
    }

    @Override
    public L2Stats stats() {
        long hit = hitCount.sum();
        long miss = missCount.sum();
        return new L2Stats("redis-l2", hit, miss, CacheStats.rate(hit, miss), -1, -1, writeCount.sum());
    }

    @Override
    public String backend() {
        return "redis-l2";
    }

    private String ns(String key) {
        return namespace + key;
    }
}
