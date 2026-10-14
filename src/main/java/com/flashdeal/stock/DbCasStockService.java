package com.flashdeal.stock;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.repository.StockLogRepository;

/**
 * 【方案 A】数据库乐观锁 CAS 扣减库存。
 *
 * <h2>核心 SQL</h2>
 * <pre>
 * UPDATE seckill_activity
 *    SET stock = stock - ?, version = version + 1
 *  WHERE id = ? AND stock &gt;= ? AND version = ?
 * </pre>
 *
 * <h2>为什么不会超卖（面试要点）</h2>
 * <ol>
 *   <li>{@code stock >= ?} 是「判断 + 扣减」在一条 UPDATE 内完成，
 *       数据库对同一行的写会串行化（H2/InnoDB 行锁），不存在「先查后改」的窗口；</li>
 *   <li>{@code version = ?} 让并发冲突显式化：两个线程读到同一个 version，
 *       只有一个能更新成功，另一个影响行数为 0，走重试；</li>
 *   <li>最终防线是订单表唯一索引 {@code uk_order_user_activity}，
 *       即使库存被多扣，也不会产生重复订单。</li>
 * </ol>
 *
 * <h2>吞吐瓶颈在哪</h2>
 * <p>所有请求竞争同一行的行锁，QPS 上限由「单行 UPDATE 的串行化能力」决定。
 * 实测数据见 README 的「两种扣减方案对比」章节。优化手段是分桶库存
 * （把 100 件拆成 10 桶 × 10 件），本项目未实现，在 docs/DESIGN.md 里列为已知边界。</p>
 *
 * <h2>重试策略</h2>
 * <p>版本冲突时重试（默认最多 {@code flashdeal.stock.cas-max-retry} 次，指数退避），
 * 库存不足则立即返回失败——因为库存不足是业务终态，重试没有意义。</p>
 */
@Service
@ConditionalOnProperty(name = "flashdeal.stock.mode", havingValue = "db-cas", matchIfMissing = true)
public class DbCasStockService implements StockService {

    private final SeckillActivityRepository activityRepository;
    private final StockLogRepository stockLogRepository;
    private final int maxRetry;

    public DbCasStockService(SeckillActivityRepository activityRepository,
                             StockLogRepository stockLogRepository,
                             @Value("${flashdeal.stock.cas-max-retry:20}") int maxRetry) {
        this.activityRepository = activityRepository;
        this.stockLogRepository = stockLogRepository;
        this.maxRetry = Math.max(1, maxRetry);
    }

    @Override
    @Transactional
    public DeductResult deduct(long activityId, long userId, int quantity, String orderNo) {
        int attempt = 0;
        while (attempt < maxRetry) {
            Optional<SeckillActivity> found = activityRepository.findById(activityId);
            if (found.isEmpty()) {
                return DeductResult.fail(DeductResult.ACTIVITY_NOT_FOUND, attempt);
            }
            SeckillActivity activity = found.get();
            if (activity.stock() < quantity) {
                // 业务终态：库存不足，重试无意义
                return DeductResult.fail(DeductResult.STOCK_NOT_ENOUGH, attempt);
            }

            int updated = activityRepository.deductStockByCas(activityId, quantity, activity.version());
            if (updated == 1) {
                int stockAfter = activity.stock() - quantity;
                stockLogRepository.insert(activityId, userId, -quantity, stockAfter, "DEDUCT_CAS", orderNo);
                return DeductResult.ok(stockAfter, attempt);
            }
            // 影响行数为 0：要么库存刚被别人抢完，要么 version 变了 -> 重试
            attempt++;
            backoff(attempt);
        }
        return DeductResult.fail(DeductResult.VERSION_CONFLICT, attempt);
    }

    @Override
    @Transactional
    public boolean rollback(long activityId, long userId, int quantity, String orderNo, String reason) {
        int updated = activityRepository.rollbackStock(activityId, quantity);
        if (updated == 1) {
            int stockAfter = activityRepository.currentStock(activityId);
            stockLogRepository.insert(activityId, userId, quantity, stockAfter, reason, orderNo);
            return true;
        }
        return false;
    }

    @Override
    public int currentStock(long activityId) {
        return activityRepository.currentStock(activityId);
    }

    @Override
    public String strategyName() {
        return "db-cas";
    }

    /** 指数退避：1ms → 2ms → 4ms ... 上限 16ms，避免冲突时把数据库打得更狠。 */
    private void backoff(int attempt) {
        long millis = Math.min(16L, 1L << Math.min(attempt, 4));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "扣减库存被中断");
        }
    }
}
