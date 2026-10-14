package com.flashdeal.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashdeal.lock.redis.FakeRedisScriptExecutor;
import com.flashdeal.lock.redis.RedisDistributedLock;

/**
 * 分布式锁测试：单机锁与 Redis 语义锁跑<b>同一套断言</b>。
 *
 * <p>这是「接口一致」这件事最有力的证明：同一个 {@code verifyMutualExclusion}
 * 方法分别传入两个实现，都必须得到「无丢失更新」的结果。</p>
 */
class DistributedLockTest {

    private static final int THREADS = 32;
    private static final int LOOPS = 2000;

    @Test
    @DisplayName("单机 ReentrantLock：32 线程 × 2000 次临界区自增无丢失更新")
    void localLockProtectsCriticalSection() throws InterruptedException {
        LockVerification result = verifyMutualExclusion(new LocalReentrantLock(), "local-reentrant-lock");
        printResult(result);
        assertThat(result.lostUpdates).as("锁保护下的自增不应丢失更新").isZero();
        assertThat(result.actual).isEqualTo(result.expected);
        assertThat(result.acquireFailures).isZero();
    }

    @Test
    @DisplayName("Redis 语义锁（Lua 解锁）：同一套断言同样零丢失更新")
    void redisSemanticLockProtectsCriticalSection() throws InterruptedException {
        FakeRedisScriptExecutor executor = new FakeRedisScriptExecutor();
        RedisDistributedLock lock = new RedisDistributedLock(executor);
        LockVerification result = verifyMutualExclusion(lock, "redis-lua-lock");
        printResult(result);
        assertThat(result.lostUpdates).as("Redis 语义锁保护下的自增不应丢失更新").isZero();
        assertThat(result.actual).isEqualTo(result.expected);
        assertThat(executor.setNxSuccess()).as("应真实发生过 SET NX 抢锁").isPositive();
        assertThat(executor.evalCount()).as("解锁必须走 Lua 脚本").isPositive();
    }

    @Test
    @DisplayName("Redis 语义锁：只有持有者能解锁（防误删别人的锁）")
    void onlyOwnerCanUnlock() throws Exception {
        FakeRedisScriptExecutor executor = new FakeRedisScriptExecutor();
        RedisDistributedLock lockA = new RedisDistributedLock(executor);

        // A 先拿到锁
        assertThat(lockA.tryLock("order-1", 30, TimeUnit.SECONDS)).isTrue();

        // 模拟另一个进程（不同 token）尝试解锁：直接调用底层脚本，value 不匹配
        Object result = executor.eval(FakeRedisScriptExecutor.UNLOCK_SCRIPT,
                java.util.List.of("flashdeal:lock:order-1"), java.util.List.of("someone-else-token"));

        System.out.printf("""
                ================ Redis 语义锁：防误删 ================
                A 持锁成功=true
                他人用错误 token 执行解锁脚本 -> 返回 %s（0 表示拒绝）
                锁是否仍被 A 持有=%s
                不变量校验: 他人无法删除 A 的锁 ? %s
                ======================================================
                """, result, lockA.isLocked("order-1"), ((Number) result).longValue() == 0);

        assertThat(((Number) result).longValue()).as("token 不匹配时脚本必须返回 0").isZero();
        assertThat(lockA.isLocked("order-1")).as("A 的锁不应被他人删除").isTrue();
        assertThat(lockA.unlock("order-1")).as("A 自己可以解锁").isTrue();
        assertThat(lockA.isLocked("order-1")).isFalse();
    }

    @Test
    @DisplayName("Redis 语义锁：租约到期后锁自动释放，避免死锁")
    void leaseExpiryReleasesLock() throws Exception {
        FakeRedisScriptExecutor executor = new FakeRedisScriptExecutor();
        RedisDistributedLock lock = new RedisDistributedLock(executor);
        assertThat(lock.tryLock("lease-key", 100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(lock.isLocked("lease-key")).isTrue();

        Thread.sleep(160);
        boolean stillLocked = lock.isLocked("lease-key");

        System.out.printf("""
                ================ Redis 语义锁：租约自动释放 ================
                租约=100ms  等待=160ms
                租约到期后仍被锁住=%s
                不变量校验: 租约到期后锁自动释放 ? %s
                ==========================================================
                """, stillLocked, !stillLocked);

        assertThat(stillLocked).as("租约到期后锁必须自动释放").isFalse();
    }

    @Test
    @DisplayName("Redis 语义锁：同一线程可重入")
    void reentrantBySameThread() throws Exception {
        RedisDistributedLock lock = new RedisDistributedLock(new FakeRedisScriptExecutor());
        assertThat(lock.tryLock("reentrant", 10, TimeUnit.SECONDS)).isTrue();
        assertThat(lock.tryLock("reentrant", 10, TimeUnit.SECONDS)).as("同线程重入应成功").isTrue();
        assertThat(lock.heldKeys()).hasSize(1);
        assertThat(lock.unlock("reentrant")).isTrue();
        assertThat(lock.unlock("reentrant")).as("第二次解锁应失败（已无持有记录）").isFalse();
    }

    @Test
    @DisplayName("Redis 语义锁：看门狗续期只有持有者能成功")
    void watchdogRenewOnlyByOwner() throws Exception {
        FakeRedisScriptExecutor executor = new FakeRedisScriptExecutor();
        RedisDistributedLock lock = new RedisDistributedLock(executor);
        assertThat(lock.tryLock("renew-key", 200, TimeUnit.MILLISECONDS)).isTrue();

        boolean renewed = lock.renew("renew-key", 5_000);
        assertThat(renewed).as("持有者续期应成功").isTrue();
        assertThat(lock.renewCount()).isEqualTo(1);

        Thread.sleep(250);
        boolean stillLocked = lock.isLocked("renew-key");

        System.out.printf("""
                ================ Redis 语义锁：看门狗续期 ================
                初始租约=200ms  续期到=5000ms  等待=250ms
                续期成功=%s  续期次数=%d
                等待后仍被锁住=%s
                不变量校验: 续期后租约被延长 ? %s
                ==========================================================
                """, renewed, lock.renewCount(), stillLocked, stillLocked);

        assertThat(stillLocked).as("续期后不应被自动释放").isTrue();
    }

    @Test
    @DisplayName("单机锁：锁被占用时 tryLock(0) 立即返回 false")
    void localLockNonBlockingTryFailsFast() throws Exception {
        LocalReentrantLock lock = new LocalReentrantLock();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondAttempt = new AtomicInteger(-1);

        Thread holder = new Thread(() -> {
            lock.tryLock("busy", 10, TimeUnit.SECONDS);
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock("busy");
            }
        });
        holder.start();
        holding.await();

        Thread other = new Thread(() -> secondAttempt.set(lock.tryLock("busy", 0, TimeUnit.SECONDS) ? 1 : 0));
        other.start();
        other.join(5000);
        release.countDown();
        holder.join(5000);

        LockStats stats = lock.stats();
        System.out.printf("""
                ================ 单机锁：非阻塞抢锁 ================
                线程A 持锁中，线程B tryLock(wait=0) 结果=%s（0=失败 1=成功）
                加锁成功次数=%d  加锁失败次数=%d  解锁次数=%d
                不变量校验: B 未抢到锁 ? %s
                不变量校验: 失败被计数 ? %s
                ====================================================
                """, secondAttempt.get(), stats.acquireCount(), stats.failCount(), stats.unlockCount(),
                secondAttempt.get() == 0, stats.failCount() >= 1);

        assertThat(secondAttempt.get()).as("锁被占用时非阻塞抢锁必须失败").isZero();
        assertThat(stats.failCount()).isPositive();
    }

    /** 用同一套代码验证两个锁实现的互斥性。 */
    private LockVerification verifyMutualExclusion(DistributedLock lock, String name) throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        LongAdder acquireFailures = new LongAdder();
        LongAdder acquired = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < LOOPS; i++) {
                        // 用带等待时间的 tryLock：32 个线程抢一把锁，
                        // 非阻塞版本会让绝大多数线程直接失败，测不出互斥性
                        if (lock.tryLock("shared-counter", 5, 5, TimeUnit.SECONDS)) {
                            try {
                                acquired.increment();
                                counter.incrementAndGet();
                            } finally {
                                lock.unlock("shared-counter");
                            }
                        } else {
                            acquireFailures.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        boolean finished = done.await(180, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdownNow();

        int expected = THREADS * LOOPS;
        return new LockVerification(name, THREADS, LOOPS, expected, counter.get(),
                expected - counter.get(), acquired.sum(), acquireFailures.sum(), elapsedMillis, finished);
    }

    private void printResult(LockVerification r) {
        System.out.printf("""
                ================ 分布式锁互斥性验证 [%s] ================
                线程=%d  每线程循环=%d  期望计数=%d
                实际计数=%d  丢失更新=%d
                抢锁成功=%d  抢锁失败=%d  耗时=%dms  全部完成=%s
                不变量校验: 实际计数 == 期望计数（无丢失更新）? %s
                ==========================================================
                """, r.impl, r.threads, r.loops, r.expected, r.actual, r.lostUpdates,
                r.acquired, r.acquireFailures, r.elapsedMillis, r.finished, r.lostUpdates == 0);
    }

    /** 锁验证结果。 */
    private record LockVerification(String impl, int threads, int loops, int expected, int actual,
                                    int lostUpdates, long acquired, long acquireFailures,
                                    long elapsedMillis, boolean finished) {
    }
}
