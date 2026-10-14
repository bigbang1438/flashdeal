package com.flashdeal.lock.redis;

import java.util.List;

/**
 * Redis 命令执行器抽象。
 *
 * <p>把「锁算法」与「Redis 连接」解耦，好处有两个：</p>
 * <ol>
 *   <li>本机没有 Redis，测试时注入 {@link FakeRedisScriptExecutor}（用
 *       ConcurrentHashMap 真实实现 SET NX PX / 过期 / EVAL 语义）即可对
 *       锁算法做真实并发测试；</li>
 *   <li>生产环境注入 {@link JedisScriptExecutor}，代码路径完全一致。</li>
 * </ol>
 */
public interface RedisScriptExecutor {

    /**
     * SET key value NX PX ttl。
     *
     * @return 成功返回 "OK"，未设置成功返回 null
     */
    String setNxPx(String key, String value, long ttlMillis);

    /** GET key。 */
    String get(String key);

    /** DEL key。 */
    long del(String key);

    /** EVAL script numKeys key... arg... */
    Object eval(String script, List<String> keys, List<String> args);

    /** 是否可用（用于健康检查与降级）。 */
    boolean available();
}
