package com.flashdeal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FlashDeal 高并发秒杀交易系统启动类。
 *
 * <p>设计目标：在没有任何外部中间件（Redis / MySQL / MQ / Docker）的机器上，
 * 仅依赖 JDK 21 + H2 文件数据库即可完整运行全部功能与压测。</p>
 */
@SpringBootApplication
@EnableScheduling
public class FlashDealApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashDealApplication.class, args);
    }
}
