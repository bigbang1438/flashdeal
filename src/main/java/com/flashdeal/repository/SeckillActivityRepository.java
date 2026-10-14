package com.flashdeal.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.flashdeal.domain.SeckillActivity;

/**
 * 秒杀活动仓储。
 *
 * <p>这里是「防超卖」的第一战场：{@link #deductStockByCas} 是方案 A 的核心 SQL。</p>
 */
@Repository
public class SeckillActivityRepository {

    private static final String COLUMNS = "id, product_id, title, seckill_price_cents, total_stock, stock, version, "
            + "start_time, end_time, status, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public SeckillActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long productId, String title, long seckillPriceCents, int totalStock,
                       LocalDateTime startTime, LocalDateTime endTime, int status) {
        String sql = """
                INSERT INTO seckill_activity
                    (product_id, title, seckill_price_cents, total_stock, stock, version, start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, productId);
            ps.setString(2, title);
            ps.setLong(3, seckillPriceCents);
            ps.setInt(4, totalStock);
            ps.setInt(5, totalStock);
            ps.setTimestamp(6, Timestamp.valueOf(startTime));
            ps.setTimestamp(7, Timestamp.valueOf(endTime));
            ps.setInt(8, status);
            return ps;
        }, keyHolder);
        return RowMappers.generatedId(keyHolder);
    }

    public Optional<SeckillActivity> findById(long id) {
        List<SeckillActivity> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM seckill_activity WHERE id = ?", RowMappers.ACTIVITY, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<SeckillActivity> findPage(int offset, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM seckill_activity ORDER BY id DESC LIMIT ? OFFSET ?",
                RowMappers.ACTIVITY, size, offset);
    }

    public List<SeckillActivity> findByStatus(int status, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM seckill_activity WHERE status = ? ORDER BY id LIMIT ?",
                RowMappers.ACTIVITY, status, limit);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM seckill_activity", Long.class);
        return total == null ? 0L : total;
    }

    /**
     * 【方案 A 核心】数据库乐观锁 CAS 扣减库存。
     *
     * <pre>
     * UPDATE seckill_activity
     *    SET stock = stock - ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
     *  WHERE id = ? AND stock &gt;= ? AND version = ?
     * </pre>
     *
     * <p><b>为什么不会超卖</b>：{@code stock >= ?} 与 {@code version = ?} 同时成立才会更新，
     * 而数据库对同一行的 UPDATE 会加行锁串行执行，因此「判断 + 扣减」是一个原子操作。
     * 影响行数为 0 说明库存不足或版本被别的线程改了，调用方重试或直接返回失败。</p>
     *
     * <p><b>为什么还要 version</b>：{@code stock >= ?} 单独就能防超卖，加上 version 是为了
     * 检测「ABA 型并发」（A 扣减后 B 回补，库存数字回到原值但业务上已经变化），
     * 同时也是可观测的冲突指标。</p>
     *
     * @return 影响行数：1 成功，0 失败（库存不足或版本冲突）
     */
    public int deductStockByCas(long activityId, int quantity, long expectedVersion) {
        return jdbc.update("""
                UPDATE seckill_activity
                   SET stock = stock - ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND stock >= ? AND version = ?
                """, quantity, activityId, quantity, expectedVersion);
    }

    /**
     * 条件扣减（用于「内存预扣 + 异步落库」方案的落库阶段）。
     *
     * <p>此时并发控制已经在内存里完成，数据库只是最终一致性的落点，
     * 但仍然保留 {@code stock >= ?} 作为最后一道防线：如果这里返回 0，
     * 说明内存与数据库出现了不一致，调用方必须告警并回补内存库存。</p>
     */
    public int deductStockUnsafe(long activityId, int quantity) {
        return jdbc.update("""
                UPDATE seckill_activity
                   SET stock = stock - ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND stock >= ?
                """, quantity, activityId, quantity);
    }

    /** 回补库存（超时未支付 / 支付失败）。 */
    public int rollbackStock(long activityId, int quantity) {
        return jdbc.update("""
                UPDATE seckill_activity
                   SET stock = stock + ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND stock + ? <= total_stock
                """, quantity, activityId, quantity);
    }

    public int updateStatus(long activityId, int status) {
        return jdbc.update("UPDATE seckill_activity SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, activityId);
    }

    /** 把到期活动批量置为已结束。 */
    public int closeExpiredActivities(LocalDateTime now) {
        return jdbc.update("""
                UPDATE seckill_activity SET status = 2, updated_at = CURRENT_TIMESTAMP
                 WHERE status = 1 AND end_time <= ?
                """, Timestamp.valueOf(now));
    }

    /** 把所有「已到开始时间但状态还是未开始」的活动置为进行中。 */
    public int openStartedActivities(LocalDateTime now) {
        return jdbc.update("""
                UPDATE seckill_activity SET status = 1, updated_at = CURRENT_TIMESTAMP
                 WHERE status = 0 AND start_time <= ? AND end_time > ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 供测试断言使用：直接读剩余库存。 */
    public int currentStock(long activityId) {
        Integer stock = jdbc.queryForObject("SELECT stock FROM seckill_activity WHERE id = ?",
                Integer.class, activityId);
        return stock == null ? -1 : stock;
    }

    /** 供测试断言使用：直接读版本号。 */
    public long currentVersion(long activityId) {
        Long version = jdbc.queryForObject("SELECT version FROM seckill_activity WHERE id = ?",
                Long.class, activityId);
        return version == null ? -1L : version;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM seckill_activity");
    }
}
