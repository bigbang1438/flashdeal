package com.flashdeal.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.flashdeal.domain.StockLog;

/**
 * 库存流水仓储：每一次库存变动都留痕，是「不超卖」结论的可审计证据。
 */
@Repository
public class StockLogRepository {

    private static final String COLUMNS = "id, activity_id, user_id, delta, stock_after, reason, order_no, created_at";

    private final JdbcTemplate jdbc;

    public StockLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(long activityId, Long userId, int delta, int stockAfter, String reason, String orderNo) {
        return jdbc.update("""
                INSERT INTO stock_log (activity_id, user_id, delta, stock_after, reason, order_no)
                VALUES (?, ?, ?, ?, ?, ?)
                """, activityId, userId, delta, stockAfter, reason, orderNo);
    }

    /** 批量写入流水（内存队列批量消费时使用）。 */
    public int batchInsert(List<StockLog> logs) {
        if (logs.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO stock_log (activity_id, user_id, delta, stock_after, reason, order_no)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        int[][] result = jdbc.batchUpdate(sql, logs, logs.size(), (ps, log) -> {
            ps.setLong(1, log.activityId());
            if (log.userId() == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, log.userId());
            }
            ps.setInt(3, log.delta());
            ps.setInt(4, log.stockAfter());
            ps.setString(5, log.reason());
            ps.setString(6, log.orderNo());
        });
        int inserted = 0;
        for (int[] batch : result) {
            for (int r : batch) {
                if (r > 0 || r == java.sql.Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    public List<StockLog> findByActivity(long activityId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM stock_log WHERE activity_id = ? ORDER BY id DESC LIMIT ?",
                RowMappers.STOCK_LOG, activityId, limit);
    }

    /** 按原因统计变动总量，用于校验「扣减总量 == 订单数」。 */
    public long sumDeltaByActivityAndReason(long activityId, String reason) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM stock_log WHERE activity_id = ? AND reason = ?",
                Long.class, activityId, reason);
        return sum == null ? 0L : sum;
    }

    public long countByActivity(long activityId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM stock_log WHERE activity_id = ?",
                Long.class, activityId);
        return total == null ? 0L : total;
    }

    /** 校验用：某活动最后一次流水的 stock_after 应与活动表当前库存一致。 */
    public Integer lastStockAfter(long activityId) {
        List<Integer> list = jdbc.queryForList(
                "SELECT stock_after FROM stock_log WHERE activity_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, activityId);
        return list.isEmpty() ? null : list.get(0);
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM stock_log");
    }

    /** 供测试插入带时间戳的流水。 */
    public int insertWithTime(long activityId, Long userId, int delta, int stockAfter, String reason,
                              String orderNo, LocalDateTime createdAt) {
        return jdbc.update("""
                INSERT INTO stock_log (activity_id, user_id, delta, stock_after, reason, order_no, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, activityId, userId, delta, stockAfter, reason, orderNo, Timestamp.valueOf(createdAt));
    }
}
