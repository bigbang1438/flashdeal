package com.flashdeal.lock.redis;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

/**
 * 真实 Jedis 实现的命令执行器（生产用，本机未连真 Redis）。
 *
 * <p>该类在本机环境<b>不会被装配</b>：只有把 {@code flashdeal.lock.mode} 设为
 * {@code redis} 并且 {@code flashdeal.redis.url} 指向可达实例时，
 * {@code RedisLockConfiguration} 才会创建它。代码本身可编译、可审阅，
 * 保证「切到真 Redis」是一次配置变更而不是一次重构。</p>
 */
public class JedisScriptExecutor implements RedisScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(JedisScriptExecutor.class);

    private final JedisPooled jedis;

    public JedisScriptExecutor(String url) {
        this.jedis = new JedisPooled(url);
    }

    @Override
    public String setNxPx(String key, String value, long ttlMillis) {
        return jedis.set(key, value, SetParams.setParams().nx().px(ttlMillis));
    }

    @Override
    public String get(String key) {
        return jedis.get(key);
    }

    @Override
    public long del(String key) {
        return jedis.del(key);
    }

    @Override
    public Object eval(String script, List<String> keys, List<String> args) {
        return jedis.eval(script, keys, args);
    }

    @Override
    public boolean available() {
        try {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            log.warn("Redis 不可达，分布式锁将降级: {}", e.getMessage());
            return false;
        }
    }
}
