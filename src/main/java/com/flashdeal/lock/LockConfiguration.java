package com.flashdeal.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.flashdeal.lock.redis.FakeRedisScriptExecutor;
import com.flashdeal.lock.redis.JedisScriptExecutor;
import com.flashdeal.lock.redis.RedisDistributedLock;
import com.flashdeal.lock.redis.RedisScriptExecutor;

/**
 * 分布式锁装配。
 *
 * <p>{@code flashdeal.lock.mode} 三选一：</p>
 * <ul>
 *   <li>{@code local}（默认）：{@link LocalReentrantLock}，单机 ReentrantLock；</li>
 *   <li>{@code redis}：真实 Jedis 连接，需要可达 Redis；</li>
 *   <li>{@code redis-fake}：Redis 语义的进程内实现，锁算法与 redis 模式完全相同，
 *       用于本机无 Redis 时的真实并发验证。</li>
 * </ul>
 */
@Configuration
public class LockConfiguration {

    /** Redis 语义锁 + 真实 Jedis 连接（需要 Redis 可达）。 */
    @Bean
    @ConditionalOnProperty(name = "flashdeal.lock.mode", havingValue = "redis")
    public RedisScriptExecutor jedisScriptExecutor(
            @org.springframework.beans.factory.annotation.Value("${flashdeal.redis.url:redis://127.0.0.1:6379}") String url) {
        return new JedisScriptExecutor(url);
    }

    /** Redis 语义锁 + 进程内执行器（本机无 Redis 时的真实并发验证路径）。 */
    @Bean
    @ConditionalOnProperty(name = "flashdeal.lock.mode", havingValue = "redis-fake")
    public RedisScriptExecutor fakeRedisScriptExecutor() {
        return new FakeRedisScriptExecutor();
    }

    @Bean
    @ConditionalOnProperty(name = "flashdeal.lock.mode", havingValue = "redis")
    public DistributedLock redisDistributedLock(RedisScriptExecutor executor) {
        return new RedisDistributedLock(executor);
    }

    @Bean
    @ConditionalOnProperty(name = "flashdeal.lock.mode", havingValue = "redis-fake")
    public DistributedLock fakeRedisDistributedLock(RedisScriptExecutor executor) {
        return new RedisDistributedLock(executor);
    }
}
