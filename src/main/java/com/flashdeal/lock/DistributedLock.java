package com.flashdeal.lock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁抽象。
 *
 * <p>两套实现共享同一接口，因此业务代码可以在「单机 ReentrantLock」与
 * 「Redis SET NX PX + Lua 释放」之间无感切换：</p>
 * <ul>
 *   <li>{@link LocalReentrantLock}：单机实现，支持可重入、公平/非公平；</li>
 *   <li>{@link com.flashdeal.lock.redis.RedisDistributedLock}：Redis 实现，
 *       加锁用 {@code SET key value NX PX ttl}，解锁用「比对 value 再删除」的
 *       Lua 脚本保证原子性，另附看门狗式续期。</li>
 * </ul>
 */
public interface DistributedLock {

    /**
     * 尝试加锁，不阻塞。
     *
     * @param key       锁键
     * @param leaseTime 租约时长（超过后锁自动释放，防止死锁）
     * @return 是否加锁成功
     */
    boolean tryLock(String key, long leaseTime, TimeUnit unit);

    /**
     * 在给定等待时间内尝试加锁。
     *
     * @param waitTime  最长等待时间
     * @param leaseTime 租约时长
     * @return 是否加锁成功
     */
    boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;

    /** 释放锁；只有持有者才能释放成功。 */
    boolean unlock(String key);

    /** 当前是否被（本进程）持有。 */
    boolean isLocked(String key);

    /** 实现名称，用于指标端点展示。 */
    String implName();
}
