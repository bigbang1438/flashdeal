package com.flashdeal.mq;

/**
 * 消息队列运行时统计。
 *
 * @param topic            主题
 * @param capacity         环形缓冲容量
 * @param publishedCount   累计入队
 * @param consumedCount    累计消费成功
 * @param failedCount      累计消费失败（含重试）
 * @param retryCount       累计重新入队重试次数
 * @param deadLetterCount  进入死信的条数
 * @param rejectedCount    因队列满被拒绝的条数
 * @param backlog          当前积压
 * @param peakBacklog      历史峰值积压（削峰效果的关键证据）
 * @param avgBatchSize     平均每批消费条数（实测值）
 * @param avgConsumeLatencyMillis 消息从入队到消费成功的平均耗时
 * @param maxConsumeLatencyMillis 消息从入队到消费成功的最大耗时
 * @param consumerThreads  消费者线程数
 * @param running          是否运行中
 */
public record MqStats(
        String topic,
        int capacity,
        long publishedCount,
        long consumedCount,
        long failedCount,
        long retryCount,
        long deadLetterCount,
        long rejectedCount,
        long backlog,
        long peakBacklog,
        double avgBatchSize,
        double avgConsumeLatencyMillis,
        long maxConsumeLatencyMillis,
        int consumerThreads,
        boolean running) {
}
