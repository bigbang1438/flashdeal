package com.flashdeal.lock;

/**
 * 锁统计快照。
 *
 * @param impl        实现名称
 * @param lockCount   当前维护的锁对象数量
 * @param acquireCount 成功加锁次数
 * @param failCount   加锁失败次数
 * @param unlockCount 成功解锁次数
 */
public record LockStats(String impl, int lockCount, long acquireCount, long failCount, long unlockCount) {
}
