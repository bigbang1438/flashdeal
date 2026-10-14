package com.flashdeal.stock;

/**
 * 库存扣减策略抽象。
 *
 * <p>两个实现对应两种工程取舍：</p>
 * <ul>
 *   <li>{@link DbCasStockService}：数据库乐观锁 CAS。强一致，吞吐受行锁限制；</li>
 *   <li>{@link MemoryStockService}：内存预扣 + 异步落库。高吞吐，最终一致。</li>
 * </ul>
 *
 * <p>两者必须都满足「不超卖、不少卖」，由并发测试统一验证。</p>
 */
public interface StockService {

    /**
     * 扣减库存。
     *
     * @param activityId 活动 ID
     * @param userId     用户 ID（用于流水）
     * @param quantity   数量
     * @param orderNo    订单号（用于流水；内存方案在落库前可能为空）
     * @return 扣减结果
     */
    DeductResult deduct(long activityId, long userId, int quantity, String orderNo);

    /**
     * 回补库存（超时未支付 / 支付失败）。
     *
     * @return 是否回补成功
     */
    boolean rollback(long activityId, long userId, int quantity, String orderNo, String reason);

    /** 查询剩余库存（读路径可能走内存或数据库，取决于策略）。 */
    int currentStock(long activityId);

    /** 策略名称，用于 /metrics 与响应体。 */
    String strategyName();
}
