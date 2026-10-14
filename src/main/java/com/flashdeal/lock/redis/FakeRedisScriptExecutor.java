package com.flashdeal.lock.redis;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis 语义的进程内实现（用于无 Redis 环境下的真实测试与降级）。
 *
 * <p><b>它到底实现了什么</b>：真实实现 {@code SET key value NX PX ttl}、
 * {@code GET}、{@code DEL}、以及本项目用到的两个 Lua 脚本
 * （「比对 value 再删除」与「比对 value 再续期」）的语义。
 * 并发正确性由 {@link ConcurrentHashMap#compute} 的原子性保证——
 * 这与 Redis 单线程执行命令的模型在「单 key 原子性」上是等价的。</p>
 *
 * <p><b>它不是什么</b>：它不是 Redis 的替代品，没有持久化、没有主从、没有集群、
 * 没有网络分区语义。生产环境请把 {@code flashdeal.lock.mode} 设为 {@code redis}。</p>
 */
public class FakeRedisScriptExecutor implements RedisScriptExecutor {

    /** 与 RedisDistributedLock 中使用的脚本保持逐字符一致，便于对照。 */
    public static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    public static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private final LongAdder setNxOk = new LongAdder();
    private final LongAdder setNxFail = new LongAdder();
    private final LongAdder evalCount = new LongAdder();
    private final AtomicInteger renewCount = new AtomicInteger();

    @Override
    public String setNxPx(String key, String value, long ttlMillis) {
        long expireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0L;
        Entry result = store.compute(key, (k, old) -> {
            if (old != null && !old.expired()) {
                return old; // 已存在且未过期 -> NX 失败
            }
            return new Entry(value, expireAt);
        });
        boolean ok = result != null && value.equals(result.value);
        if (ok) {
            setNxOk.increment();
            return "OK";
        }
        setNxFail.increment();
        return null;
    }

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public long del(String key) {
        return store.remove(key) != null ? 1L : 0L;
    }

    @Override
    public Object eval(String script, List<String> keys, List<String> args) {
        evalCount.increment();
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        String key = keys.get(0);
        String expected = args == null || args.isEmpty() ? null : args.get(0);
        if (UNLOCK_SCRIPT.equals(script)) {
            Entry entry = store.get(key);
            if (entry == null || entry.expired() || !entry.value.equals(expected)) {
                return 0L;
            }
            store.remove(key, entry);
            return 1L;
        }
        if (RENEW_SCRIPT.equals(script)) {
            Entry entry = store.get(key);
            if (entry == null || entry.expired() || !entry.value.equals(expected)) {
                return 0L;
            }
            long ttl = Long.parseLong(args.get(1));
            store.put(key, new Entry(entry.value, System.currentTimeMillis() + ttl));
            renewCount.incrementAndGet();
            return 1L;
        }
        throw new UnsupportedOperationException("未实现的脚本: " + script);
    }

    @Override
    public boolean available() {
        return true;
    }

    /** 脚本执行次数（解锁 + 续期）。 */
    public long evalCount() {
        return evalCount.sum();
    }

    /** 续期成功次数。 */
    public int renewCount() {
        return renewCount.get();
    }

    /** SET NX 成功/失败次数，用于验证锁竞争确实发生。 */
    public long setNxSuccess() {
        return setNxOk.sum();
    }

    public long setNxFailure() {
        return setNxFail.sum();
    }

    /** 直接写入一个键（测试夹具用，模拟「别人的锁」）。 */
    public void seed(String key, String value, long ttlMillis) {
        store.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    public static String randomToken() {
        return UUID.randomUUID().toString();
    }

    /** 键值条目。 */
    private record Entry(String value, long expireAtMillis) {
        boolean expired() {
            return expireAtMillis > 0 && System.currentTimeMillis() > expireAtMillis;
        }
    }
}
