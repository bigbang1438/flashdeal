package com.flashdeal.support;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.flashdeal.domain.ActivityStatus;
import com.flashdeal.repository.IdempotentTokenRepository;
import com.flashdeal.repository.ProductRepository;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.SeckillOrderRepository;
import com.flashdeal.repository.StockLogRepository;
import com.flashdeal.service.ActivityService;

/**
 * 集成测试基类：提供数据清理与测试夹具构造。
 *
 * <p>所有测试都用真实 Spring 容器 + 真实 H2，不做 mock；
 * 每个测试方法前清空业务表，保证结果可复现。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected SeckillActivityRepository activityRepository;
    @Autowired
    protected SeckillOrderRepository orderRepository;
    @Autowired
    protected StockLogRepository stockLogRepository;
    @Autowired
    protected IdempotentTokenRepository idempotentTokenRepository;
    @Autowired
    protected ActivityService activityService;

    /** 每个测试前清空业务表（外键无关，直接按依赖倒序删）。 */
    protected void cleanDatabase() {
        stockLogRepository.deleteAll();
        orderRepository.deleteAll();
        idempotentTokenRepository.deleteAll();
        activityRepository.deleteAll();
        productRepository.deleteAll();
        activityService.clearCache();
    }

    /** 创建一个上架商品。 */
    protected long createProduct(String name, long priceCents) {
        return productRepository.insert(name, "集成测试商品", priceCents, null, 1);
    }

    /** 创建一个「正在进行中」的秒杀活动。 */
    protected long createRunningActivity(long productId, int totalStock) {
        LocalDateTime now = LocalDateTime.now();
        return activityRepository.insert(productId, "测试活动-" + SEQ.incrementAndGet(),
                9900L, totalStock, now.minusMinutes(5), now.plusMinutes(30), ActivityStatus.RUNNING.code());
    }

    /** 创建一个「已结束」的活动。 */
    protected long createEndedActivity(long productId, int totalStock) {
        LocalDateTime now = LocalDateTime.now();
        return activityRepository.insert(productId, "已结束活动-" + SEQ.incrementAndGet(),
                9900L, totalStock, now.minusHours(2), now.minusHours(1), ActivityStatus.ENDED.code());
    }

    /** 创建测试用户 ID，保证跨测试不冲突。 */
    protected long nextUserId() {
        return 1_000_000L + SEQ.incrementAndGet();
    }

    /** 生成唯一幂等令牌。 */
    protected String nextToken() {
        return "tok-" + System.nanoTime() + "-" + SEQ.incrementAndGet();
    }
}
