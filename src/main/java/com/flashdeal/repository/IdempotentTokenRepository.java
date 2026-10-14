package com.flashdeal.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.flashdeal.idempotent.IdempotentToken;

/**
 * 幂等令牌仓储。
 *
 * <p>令牌表上的唯一索引 {@code uk_idem_token} 是幂等性的最终保证：
 * 两个并发请求同时插入同一个 token，数据库只会让一个成功。</p>
 */
@Repository
public class IdempotentTokenRepository {

    private static final String COLUMNS = "id, token, user_id, request_hash, status, result_json, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public IdempotentTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 抢占令牌（INSERT）。
     *
     * @return 抢占成功返回 true；token 已存在返回 false（不抛异常，便于主链路判断）
     */
    public boolean tryAcquire(String token, long userId, String requestHash) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_token (token, user_id, request_hash, status)
                    VALUES (?, ?, ?, 0)
                    """, token, userId, requestHash);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<IdempotentToken> findByToken(String token) {
        List<IdempotentToken> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM idempotent_token WHERE token = ?",
                (rs, rowNum) -> new IdempotentToken(
                        rs.getLong("id"),
                        rs.getString("token"),
                        rs.getLong("user_id"),
                        rs.getString("request_hash"),
                        rs.getInt("status"),
                        rs.getString("result_json"),
                        RowMappers.toLocalDateTime(rs.getTimestamp("created_at")),
                        RowMappers.toLocalDateTime(rs.getTimestamp("updated_at"))),
                token);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 标记为成功并缓存结果 JSON。 */
    public int markSuccess(String token, String resultJson) {
        return jdbc.update("""
                UPDATE idempotent_token SET status = 1, result_json = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE token = ? AND status = 0
                """, resultJson, token);
    }

    /** 标记为失败，允许同 token 重试。 */
    public int markFailed(String token) {
        return jdbc.update("""
                UPDATE idempotent_token SET status = 2, updated_at = CURRENT_TIMESTAMP
                 WHERE token = ? AND status = 0
                """, token);
    }

    /**
     * 把令牌状态 CAS 回「处理中」（2 → 0，或刷新超时的 0）。
     *
     * <p>这是幂等重试的关键：只有一条 UPDATE 影响行数为 1 的线程才能接管执行权，
     * 从而避免「多个线程同时认为自己是重试者」。</p>
     *
     * @return 是否抢占成功
     */
    public boolean tryReacquire(String token) {
        return jdbc.update("""
                UPDATE idempotent_token SET status = 0, updated_at = CURRENT_TIMESTAMP
                 WHERE token = ? AND status IN (0, 2)
                """, token) == 1;
    }

    /** 清理过期令牌（默认保留 24 小时），防止表无限增长。 */
    public int deleteExpired(LocalDateTime before) {
        return jdbc.update("DELETE FROM idempotent_token WHERE created_at < ?", Timestamp.valueOf(before));
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_token", Long.class);
        return total == null ? 0L : total;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM idempotent_token");
    }
}
