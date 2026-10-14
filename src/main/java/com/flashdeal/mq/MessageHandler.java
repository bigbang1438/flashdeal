package com.flashdeal.mq;

import java.util.List;
import java.util.Set;

/**
 * 批量消息处理器。
 *
 * <p>队列以「批」为单位调用处理器，处理器把一批消息合并成一次数据库往返
 * （批量 UPDATE + 批量 INSERT），这是削峰之外的第二层吞吐优化。</p>
 *
 * <p>失败语义：</p>
 * <ul>
 *   <li>返回非空集合：集合内的 key 视为失败，其余成功；失败项按 {@code maxRetry} 重试；</li>
 *   <li>抛异常：整批视为失败，全部重试。</li>
 * </ul>
 *
 * @param <T> 载荷类型
 */
@FunctionalInterface
public interface MessageHandler<T> {

    /**
     * 批量处理。
     *
     * @param batch 一批消息（非空）
     * @return 处理失败的消息 key 集合；全部成功返回空集合
     */
    Set<String> handleBatch(List<Message<T>> batch) throws Exception;
}
