package com.flashdeal.stock;

import com.flashdeal.mq.MqStats;

/**
 * 内存预扣方案统计。
 *
 * @param trackedActivities   已预热的活动数
 * @param deductCount         内存扣减成功次数
 * @param rejectCount         库存不足拒绝次数
 * @param casRetryTotal       CAS 重试总次数（冲突强度）
 * @param casRetryMax         单次扣减的最大 CAS 重试次数
 * @param persistFailCount    异步落库失败次数（必须为 0，否则内存与数据库不一致）
 * @param backlog             落库队列积压
 * @param queueStats          落库队列完整统计
 */
public record MemoryStockStats(
        int trackedActivities,
        long deductCount,
        long rejectCount,
        long casRetryTotal,
        long casRetryMax,
        long persistFailCount,
        long backlog,
        MqStats queueStats) {
}
