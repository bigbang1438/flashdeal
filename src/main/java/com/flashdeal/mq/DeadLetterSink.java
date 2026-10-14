package com.flashdeal.mq;

/**
 * 死信落盘接口。
 *
 * <p>队列本身不依赖任何存储，由上层决定死信写到 H2、文件还是日志。</p>
 *
 * @param <T> 载荷类型
 */
@FunctionalInterface
public interface DeadLetterSink<T> {

    /** 保存一条死信；实现方不应抛异常（否则会被队列吞掉并打日志）。 */
    void save(Message<T> message, String errorMessage);
}
