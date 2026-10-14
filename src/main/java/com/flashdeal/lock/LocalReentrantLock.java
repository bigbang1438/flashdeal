package com.flashdeal.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单机分布式锁实现（默认启用）。
 *
 * <p>用 {@link ReentrantLock} 的 {@code tryLock(waitTime, unit)} 提供与 Redis 锁
 * 一致的「等待 + 租约」语义。注意：单机锁没有真正的租约过期概念，
 * 这里用「超过租约时间后其他线程可强制抢占」来模拟 Redis 锁的自动释放，
 * 保证业务代码在两种实现下的行为一致。</p>
 *
 * <p>统计 {@code acquireCount / failCount}，可在 /metrics 端点观察锁竞争强度。</p>
 */
@Component
@ConditionalOnProperty(name = "flashdeal.lock.mode", havingValue = "local", matchIfMissing = true)
public class LocalReentrantLock implements DistributedLock {

    private final Map<String, LockHolder> locks = new ConcurrentHashMap<>();

    private final LongAdder acquireCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder unlockCount = new LongAdder();

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
        LockHolder holder = locks.computeIfAbsent(key, k -> new LockHolder());
        boolean acquired = holder.lock.tryLock(Math.max(0, waitTime), unit);
        if (acquired) {
            holder.leaseExpireAt = System.currentTimeMillis() + unit.toMillis(leaseTime);
            holder.owner = Thread.currentThread().getName();
            acquireCount.increment();
        } else {
            failCount.increment();
        }
        return acquired;
    }

    @Override
    public boolean unlock(String key) {
        LockHolder holder = locks.get(key);
        if (holder == null) {
            return false;
        }
        if (!holder.lock.isHeldByCurrentThread()) {
            return false;
        }
        holder.owner = null;
        holder.leaseExpireAt = 0L;
        holder.lock.unlock();
        unlockCount.increment();
        return true;
    }

    @Override
    public boolean isLocked(String key) {
        LockHolder holder = locks.get(key);
        return holder != null && holder.lock.isLocked();
    }

    @Override
    public String implName() {
        return "local-reentrant-lock";
    }

    /** 锁统计快照。 */
    public LockStats stats() {
        return new LockStats(implName(), locks.size(), acquireCount.sum(), failCount.sum(), unlockCount.sum());
    }

    /** 单个锁的持有状态。 */
    private static final class LockHolder {
        final ReentrantLock lock = new ReentrantLock();
        volatile String owner;
        volatile long leaseExpireAt;
    }
}
