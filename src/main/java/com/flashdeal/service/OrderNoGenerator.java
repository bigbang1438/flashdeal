package com.flashdeal.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 订单号生成器。
 *
 * <p>格式：{@code FD + yyyyMMddHHmmss + 6 位秒内序号 + 2 位机器位}，共 22 字符。</p>
 *
 * <p>为什么不用 UUID：订单号要能看出时间、要短、要能作为分库分表键。
 * 秒内序号用 {@link AtomicInteger} 自增，超过 999999 则回绕并依赖数据库唯一索引兜底
 * （实际压测峰值远低于此上限）。</p>
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int SEQUENCE_MOD = 1_000_000;

    private final AtomicInteger sequence = new AtomicInteger();
    private final String machineId;

    public OrderNoGenerator(@org.springframework.beans.factory.annotation.Value("${flashdeal.order.machine-id:01}")
                            String machineId) {
        this.machineId = machineId.length() >= 2 ? machineId.substring(0, 2) : "01";
    }

    public String next() {
        return next(LocalDateTime.now());
    }

    /** 便于测试注入固定时间。 */
    public String next(LocalDateTime now) {
        int seq = Math.floorMod(sequence.getAndIncrement(), SEQUENCE_MOD);
        return "FD" + now.format(FORMATTER) + String.format("%06d", seq) + machineId;
    }
}
