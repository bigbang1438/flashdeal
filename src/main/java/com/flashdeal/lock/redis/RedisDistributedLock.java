package com.flashdeal.lock.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.flashdeal.lock.DistributedLock;
import com.flashdeal.lock.LockStats;

/**
 * 基于 Redis 语义的分布式锁实现（接口与单机锁完全一致）。
 *
 * <h2>加锁</h2>
 * <pre>SET lock:key &lt;uuid&gt; NX PX &lt;leaseMillis&gt;</pre>
 * 一条命令完成「不存在则写入 + 设置过期」，原子性由 Redis 保证；
 * value 用 UUID 标识持有者，避免 A 超时释放了 B 的锁。
 *
 * <h2>解锁（Lua 脚本，比对持有者再删除）</h2>
 * <pre>
 * if redis.call('get', KEYS[1]) == ARGV[1] then
 *   return redis.call('del', KEYS[1])
 * else
 *   return 0
 * end
 * </pre>
 * GET + DEL 两条命令非原子，可能删掉别人刚拿到的锁；Lua 在 Redis 中单线程执行，
 * 因此「判断 + 删除」是一个原子操作。
 *
 * <h2>续期（看门狗）</h2>
 * 业务执行时间可能超过租约，这里提供 {@link #renew(String, long)}：
 * 同样用 Lua 比对 value 后 {@code PEXPIRE}，只有持有者能续期。
 *
 * <h2>无 Redis 环境如何测试</h2>
 * 依赖 {@link RedisScriptExecutor} 注入 {@link FakeRedisScriptExecutor}，
 * 后者真实实现上述命令语义，因此并发测试跑的是同一套锁算法。
 */
public class RedisDistributedLock implements DistributedLock {

    /** 解锁脚本：比对 value 再删除。 */
    public static final String UNLOCK_SCRIPT = FakeRedisScriptExecutor.UNLOCK_SCRIPT;

    /** 续期脚本：比对 value 再 PEXPIRE。 */
    public static final String RENEW_SCRIPT = FakeRedisScriptExecutor.RENEW_SCRIPT;

    private static final String PREFIX = "flashdeal:lock:";

    private final RedisScriptExecutor executor;
    /** 每个线程持有的锁 token，保证可重入判断与正确释放。 */
    private final ThreadLocal<ConcurrentHashMap<String, String>> heldTokens =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private final LongAdder acquireCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder unlockCount = new LongAdder();
    private final LongAdder renewCount = new LongAdder();

    public RedisDistributedLock(RedisScriptExecutor executor) {
        this.executor = executor;
    }

    @Override
    public boolean tryLock(String key, long leaseTime, TimeUnit unit) {
        try {
            return tryLock(key, 0, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failCount.increment();
            return false;
        }
    }

    @Override
    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        String redisKey = PREFIX + key;
        ConcurrentHashMap<String, String> held = heldTokens.get();
        String existing = held.get(redisKey);
        if (existing != null) {
            // 可重入：同一线程重复加锁直接成功并续期
            executor.eval(RENEW_SCRIPT, List.of(redisKey), List.of(existing, Long.toString(unit.toMillis(leaseTime))));
            acquireCount.increment();
            return true;
        }

        String token = UUID.randomUUID().toString();
        long leaseMillis = unit.toMillis(leaseTime);
        long deadline = System.nanoTime() + unit.toNanos(Math.max(0, waitTime));
        int spinCount = 0;
        do {
            if ("OK".equals(executor.setNxPx(redisKey, token, leaseMillis))) {
                held.put(redisKey, token);
                acquireCount.increment();
                return true;
            }
            if (waitTime <= 0) {
                break;
            }
            // 自旋等待，退避到 1ms 以内，避免空转打满 CPU
            long remainNanos = deadline - System.nanoTime();
            if (remainNanos <= 0) {
                break;
            }
            long sleepNanos = Math.min(remainNanos, 100_000L * (1 + (spinCount++ % 10)));
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        } while (System.nanoTime() < deadline);

        failCount.increment();
        return false;
    }

    @Override
    public boolean unlock(String key) {
        String redisKey = PREFIX + key;
        ConcurrentHashMap<String, String> held = heldTokens.get();
        String token = held.remove(redisKey);
        if (token == null) {
            return false;
        }
        Object result = executor.eval(UNLOCK_SCRIPT, List.of(redisKey), List.of(token));
        boolean ok = toLong(result) > 0;
        if (ok) {
            unlockCount.increment();
        }
        return ok;
    }

    /**
     * 看门狗续期：仅持有者可续期。
     *
     * @return 续期是否成功
     */
    public boolean renew(String key, long leaseMillis) {
        String redisKey = PREFIX + key;
        String token = heldTokens.get().get(redisKey);
        if (token == null) {
            return false;
        }
        Object result = executor.eval(RENEW_SCRIPT, List.of(redisKey), List.of(token, Long.toString(leaseMillis)));
        boolean ok = toLong(result) > 0;
        if (ok) {
            renewCount.increment();
        }
        return ok;
    }

    @Override
    public boolean isLocked(String key) {
        return executor.get(PREFIX + key) != null;
    }

    @Override
    public String implName() {
        return "redis-lua-lock";
    }

    /** 锁统计快照。 */
    public LockStats stats() {
        return new LockStats(implName(), 0, acquireCount.sum(), failCount.sum(), unlockCount.sum());
    }

    /** 续期次数（看门狗生效证明）。 */
    public long renewCount() {
        return renewCount.sum();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        return 0L;
    }

    /** 便于测试断言：当前线程持有的锁键列表。 */
    public List<String> heldKeys() {
        return new ArrayList<>(heldTokens.get().keySet());
    }
}
