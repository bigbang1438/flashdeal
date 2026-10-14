package com.flashdeal.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.flashdeal.domain.Product;

/**
 * 商品仓储（JdbcTemplate + 手写 SQL）。
 *
 * <p>不使用 JPA / MyBatis：秒杀链路的 SQL 需要精确控制（乐观锁 CAS、批量插入、
 * LIMIT/OFFSET 分页），手写 SQL 比 ORM 生成的更可预测，也更容易解释
 * 「为什么这条语句不会超卖」。</p>
 */
@Repository
public class ProductRepository {

    private static final String COLUMNS =
            "id, name, description, price_cents, image_url, status, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, String description, long priceCents, String imageUrl, int status) {
        String sql = "INSERT INTO product (name, description, price_cents, image_url, status) VALUES (?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, priceCents);
            ps.setString(4, imageUrl);
            ps.setInt(5, status);
            return ps;
        }, keyHolder);
        return RowMappers.generatedId(keyHolder);
    }

    public Optional<Product> findById(long id) {
        List<Product> list = jdbc.query("SELECT " + COLUMNS + " FROM product WHERE id = ?", RowMappers.PRODUCT, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Product> findPage(int offset, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM product ORDER BY id DESC LIMIT ? OFFSET ?",
                RowMappers.PRODUCT, size, offset);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        return total == null ? 0L : total;
    }

    public int updateStatus(long id, int status) {
        return jdbc.update("UPDATE product SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
    }

    public int update(Product product) {
        return jdbc.update("""
                UPDATE product SET name = ?, description = ?, price_cents = ?, image_url = ?, status = ?,
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, product.name(), product.description(), product.priceCents(),
                product.imageUrl(), product.status(), product.id());
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM product");
    }

    /** 供测试使用：直接按名字查。 */
    public Optional<Product> findByName(String name) {
        List<Product> list = jdbc.query("SELECT " + COLUMNS + " FROM product WHERE name = ?",
                RowMappers.PRODUCT, name);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 供测试使用：自定义 RowMapper 查询，便于统计聚合结果。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args);
    }
}
