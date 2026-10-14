package com.flashdeal.idempotent;

/**
 * 幂等统计快照。
 *
 * @param acquiredCount  首次获取执行权次数
 * @param replayCount    命中缓存响应（真正的幂等重放）次数
 * @param conflictCount  参数不一致冲突次数
 * @param processingCount 并发重复提交（处理中）次数
 * @param tokenCount     令牌表记录数
 */
public record IdempotentStats(long acquiredCount, long replayCount, long conflictCount,
                              long processingCount, long tokenCount) {
}
