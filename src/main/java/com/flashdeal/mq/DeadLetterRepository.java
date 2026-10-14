package com.flashdeal.mq;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 死信与消费幂等日志仓储。
 */
@Repository
public class DeadLetterRepository {

    private final JdbcTemplate jdbc;

    public DeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入死信。 */
    public int save(String topic, String msgKey, String payload, int attempt, String errorMessage) {
        return jdbc.update("""
                INSERT INTO mq_dead_letter (topic, msg_key, payload, attempt, error_message)
                VALUES (?, ?, ?, ?, ?)
                """, topic, msgKey, truncate(payload, 4000), attempt, truncate(errorMessage, 1000));
    }

    /** 分页查询死信。 */
    public List<DeadLetterRecord> findPage(String topic, int offset, int size) {
        String sql = "SELECT id, topic, msg_key, payload, attempt, error_message, created_at FROM mq_dead_letter "
                + (topic == null ? "" : "WHERE topic = ? ")
                + "ORDER BY id DESC LIMIT ? OFFSET ?";
        Object[] args = topic == null ? new Object[] {size, offset} : new Object[] {topic, size, offset};
        return jdbc.query(sql, (rs, rowNum) -> new DeadLetterRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("msg_key"),
                rs.getString("payload"),
                rs.getInt("attempt"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toLocalDateTime()), args);
    }

    public long count(String topic) {
        Long total = topic == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM mq_dead_letter", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM mq_dead_letter WHERE topic = ?", Long.class, topic);
        return total == null ? 0L : total;
    }

    /** 消费幂等：INSERT 成功说明是第一次消费。 */
    public boolean markConsumed(String topic, String msgKey) {
        try {
            jdbc.update("INSERT INTO mq_consume_log (topic, msg_key) VALUES (?, ?)", topic, msgKey);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public boolean alreadyConsumed(String topic, String msgKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mq_consume_log WHERE topic = ? AND msg_key = ?", Long.class, topic, msgKey);
        return count != null && count > 0;
    }

    public int deleteAll() {
        int a = jdbc.update("DELETE FROM mq_dead_letter");
        int b = jdbc.update("DELETE FROM mq_consume_log");
        return a + b;
    }

    /** 清理历史死信。 */
    public int deleteBefore(java.time.LocalDateTime before) {
        return jdbc.update("DELETE FROM mq_dead_letter WHERE created_at < ?", Timestamp.valueOf(before));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 死信记录。
     */
    public record DeadLetterRecord(Long id, String topic, String msgKey, String payload,
                                   int attempt, String errorMessage, java.time.LocalDateTime createdAt) {
    }

    /** 供测试使用：读取所有死信 key。 */
    public List<String> allKeys(String topic) {
        List<String> keys = new ArrayList<>();
        jdbc.query("SELECT msg_key FROM mq_dead_letter WHERE topic = ? ORDER BY id",
                rs -> { keys.add(rs.getString("msg_key")); }, topic);
        return keys;
    }
}
