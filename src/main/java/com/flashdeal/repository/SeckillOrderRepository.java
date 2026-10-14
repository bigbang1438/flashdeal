package com.flashdeal.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.flashdeal.domain.SeckillOrder;

/**
 * 秒杀订单仓储。
 *
 * <p>订单表上有唯一索引 {@code uk_order_user_activity}，它是「一个用户只能抢到一件」
 * 的最终防线——即使前面的库存扣减被绕过，数据库也会用唯一约束兜住重复下单。</p>
 */
@Repository
public class SeckillOrderRepository {

    private static final String COLUMNS = "id, order_no, user_id, activity_id, product_id, quantity, amount_cents, "
            + "status, idempotent_key, create_path, created_at, paid_at, expire_at, updated_at";

    private static final String GUARD_HASH = "user-activity-guard";

    private final JdbcTemplate jdbc;

    public SeckillOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入订单，返回主键；唯一约束冲突会抛出 DataIntegrityViolationException。 */
    public long insert(SeckillOrder order) {
        String sql = """
                INSERT INTO seckill_order
                    (order_no, user_id, activity_id, product_id, quantity, amount_cents,
                     status, idempotent_key, create_path, expire_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.orderNo());
            ps.setLong(2, order.userId());
            ps.setLong(3, order.activityId());
            ps.setLong(4, order.productId());
            ps.setInt(5, order.quantity());
            ps.setLong(6, order.amountCents());
            ps.setInt(7, order.status());
            ps.setString(8, order.idempotentKey());
            ps.setString(9, order.createPath());
            ps.setTimestamp(10, Timestamp.valueOf(order.expireAt()));
            return ps;
        }, keyHolder);
        return RowMappers.generatedId(keyHolder);
    }

    /**
     * 批量插入（内存队列的批量消费路径使用）。
     *
     * @return 实际插入成功的行数
     */
    public int batchInsert(List<SeckillOrder> orders) {
        if (orders.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO seckill_order
                    (order_no, user_id, activity_id, product_id, quantity, amount_cents,
                     status, idempotent_key, create_path, expire_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        int[] result = jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                SeckillOrder order = orders.get(i);
                ps.setString(1, order.orderNo());
                ps.setLong(2, order.userId());
                ps.setLong(3, order.activityId());
                ps.setLong(4, order.productId());
                ps.setInt(5, order.quantity());
                ps.setLong(6, order.amountCents());
                ps.setInt(7, order.status());
                ps.setString(8, order.idempotentKey());
                ps.setString(9, order.createPath());
                ps.setTimestamp(10, Timestamp.valueOf(order.expireAt()));
            }

            @Override
            public int getBatchSize() {
                return orders.size();
            }
        });
        int inserted = 0;
        for (int r : result) {
            if (r > 0 || r == Statement.SUCCESS_NO_INFO) {
                inserted++;
            }
        }
        return inserted;
    }

    public Optional<SeckillOrder> findByOrderNo(String orderNo) {
        List<SeckillOrder> list = jdbc.query("SELECT " + COLUMNS + " FROM seckill_order WHERE order_no = ?",
                RowMappers.ORDER, orderNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<SeckillOrder> findByUserAndActivity(long userId, long activityId) {
        List<SeckillOrder> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM seckill_order WHERE user_id = ? AND activity_id = ?",
                RowMappers.ORDER, userId, activityId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<SeckillOrder> findByIdempotentKey(String key) {
        List<SeckillOrder> list = jdbc.query("SELECT " + COLUMNS + " FROM seckill_order WHERE idempotent_key = ?",
                RowMappers.ORDER, key);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<SeckillOrder> findPage(Long activityId, Integer status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM seckill_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (activityId != null) {
            sql.append(" AND activity_id = ?");
            args.add(activityId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.query(sql.toString(), RowMappers.ORDER, args.toArray());
    }

    public long count(Long activityId, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM seckill_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (activityId != null) {
            sql.append(" AND activity_id = ?");
            args.add(activityId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 支付：只有「待支付」状态才能改成「已支付」，用状态条件做 CAS 防重复支付。 */
    public int markPaid(String orderNo, LocalDateTime paidAt) {
        return jdbc.update("""
                UPDATE seckill_order SET status = 1, paid_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE order_no = ? AND status = 0
                """, Timestamp.valueOf(paidAt), orderNo);
    }

    /** 取消订单（超时未支付）。 */
    public int markCancelled(String orderNo) {
        return jdbc.update("""
                UPDATE seckill_order SET status = 2, updated_at = CURRENT_TIMESTAMP
                 WHERE order_no = ? AND status = 0
                """, orderNo);
    }

    /**
     * 找出超时未支付的订单（分页扫描）。
     *
     * <p>定时任务用它驱动「回补库存」，{@code LIMIT} 保证单次扫描不会长时间占用连接。</p>
     */
    public List<SeckillOrder> findExpiredPendingOrders(LocalDateTime now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM seckill_order "
                        + "WHERE status = 0 AND expire_at <= ? ORDER BY id LIMIT ?",
                RowMappers.ORDER, Timestamp.valueOf(now), limit);
    }

    /** 按活动统计各状态订单数，用于 /metrics 与看板。 */
    public long countByActivityAndStatus(long activityId, int status) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE activity_id = ? AND status = ?",
                Long.class, activityId, status);
        return total == null ? 0L : total;
    }

    /**
     * 【用户维度去重闸门】同步原子抢占「用户 × 活动」名额。
     *
     * <p>订单是<b>异步落库</b>的，因此「查订单表判断用户是否已下单」存在竞态窗口：
     * 两个并发请求可能都查不到订单，于是都扣了库存，最后在批量 INSERT 时撞唯一索引，
     * 那时库存已经多扣了。</p>
     *
     * <p>这里借用幂等令牌表的唯一索引做一次<b>同步 INSERT 抢占</b>：</p>
     * <ul>
     *   <li>INSERT 成功 → 该用户在这个活动上还没有订单，可以继续扣库存；</li>
     *   <li>INSERT 冲突 → 已有订单或正在下单，直接拒绝，<b>不会多扣库存</b>。</li>
     * </ul>
     *
     * <p>订单表的 {@code uk_order_user_activity} 仍然是最终防线，但正常情况下不会被触发。</p>
     *
     * @return 抢占成功返回 true
     */
    public boolean tryAcquireUserActivityGuard(long userId, long activityId) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_token (token, user_id, request_hash, status)
                    VALUES (?, ?, ?, 1)
                    """, guardKey(userId, activityId), userId, GUARD_HASH);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** 释放「用户 × 活动」名额（扣库存失败等场景，允许用户重试）。 */
    public int releaseUserActivityGuard(long userId, long activityId) {
        return jdbc.update("DELETE FROM idempotent_token WHERE token = ? AND request_hash = ?",
                guardKey(userId, activityId), GUARD_HASH);
    }

    /** 名额键：固定前缀，便于运维识别与清理。 */
    private static String guardKey(long userId, long activityId) {
        return "ua:" + userId + ":" + activityId;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM seckill_order");
    }
}
