package com.flashdeal.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.RowMapper;

import com.flashdeal.domain.Product;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.domain.SeckillOrder;
import com.flashdeal.domain.StockLog;

/**
 * 行映射器集合。
 *
 * <p>不用 JPA / MyBatis：秒杀链路的 SQL 需要精确控制（乐观锁 CAS、批量插入、
 * {@code LIMIT ? OFFSET ?}），手写 SQL 比 ORM 生成的更可预测，也更容易解释
 * 「为什么这条语句不会超卖」。</p>
 */
public final class RowMappers {

    private RowMappers() {
    }

    public static final RowMapper<Product> PRODUCT = (ResultSet rs, int rowNum) -> new Product(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getLong("price_cents"),
            rs.getString("image_url"),
            rs.getInt("status"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    public static final RowMapper<SeckillActivity> ACTIVITY = (ResultSet rs, int rowNum) -> new SeckillActivity(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getString("title"),
            rs.getLong("seckill_price_cents"),
            rs.getInt("total_stock"),
            rs.getInt("stock"),
            rs.getLong("version"),
            toLocalDateTime(rs.getTimestamp("start_time")),
            toLocalDateTime(rs.getTimestamp("end_time")),
            rs.getInt("status"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    public static final RowMapper<SeckillOrder> ORDER = (ResultSet rs, int rowNum) -> new SeckillOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("user_id"),
            rs.getLong("activity_id"),
            rs.getLong("product_id"),
            rs.getInt("quantity"),
            rs.getLong("amount_cents"),
            rs.getInt("status"),
            rs.getString("idempotent_key"),
            rs.getString("create_path"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("paid_at")),
            toLocalDateTime(rs.getTimestamp("expire_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    public static final RowMapper<StockLog> STOCK_LOG = (ResultSet rs, int rowNum) -> new StockLog(
            rs.getLong("id"),
            rs.getLong("activity_id"),
            rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
            rs.getInt("delta"),
            rs.getInt("stock_after"),
            rs.getString("reason"),
            rs.getString("order_no"),
            toLocalDateTime(rs.getTimestamp("created_at")));

    static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 把 SQLException 包装成带上下文的运行时异常，便于定位。 */
    static RuntimeException wrap(String sql, SQLException e) {
        return new IllegalStateException("SQL 执行失败: " + sql + " -> " + e.getMessage(), e);
    }

    /**
     * 从 KeyHolder 中取出自增主键。
     *
     * <p>H2 在 {@code RETURN_GENERATED_KEYS} 模式下会返回整行（含默认值列），
     * 直接用 {@code keyHolder.getKey()} 会抛 "multiple keys"；
     * 这里显式按列名 {@code id} 取值，兼容 H2 与 MySQL。</p>
     */
    public static long generatedId(org.springframework.jdbc.support.KeyHolder keyHolder) {
        java.util.Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            Number single = keyHolder.getKey();
            return single == null ? -1L : single.longValue();
        }
        for (java.util.Map.Entry<String, Object> entry : keys.entrySet()) {
            if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number n) {
                return n.longValue();
            }
        }
        Number single = keyHolder.getKey();
        return single == null ? -1L : single.longValue();
    }
}
