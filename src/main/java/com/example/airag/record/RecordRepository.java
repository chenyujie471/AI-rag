package com.example.airag.record;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 两张提问记录表的读写。表名来自 {@link RecordType},不拼接外部输入。
 */
@Repository
public class RecordRepository {

    private static final RowMapper<QuestionRecord> ROW_MAPPER = (rs, rowNum) -> {
        QuestionRecord record = new QuestionRecord();
        record.setId(rs.getLong("id"));
        record.setUserId(rs.getLong("user_id"));
        record.setUsername(rs.getString("username"));
        record.setSessionId(rs.getString("session_id"));
        record.setQuestion(rs.getString("question"));
        record.setAnswer(rs.getString("answer"));
        record.setAppId(rs.getString("app_id"));
        record.setPromptTokens(rs.getInt("prompt_tokens"));
        record.setCompletionTokens(rs.getInt("completion_tokens"));
        record.setTotalTokens(rs.getInt("total_tokens"));
        record.setCostQuota(rs.getInt("cost_quota"));
        record.setStatus(rs.getString("status"));
        record.setErrorMsg(rs.getString("error_msg"));
        record.setDurationMs(rs.getLong("duration_ms"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        record.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        return record;
    };

    private final JdbcTemplate jdbcTemplate;

    public RecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(RecordType type, QuestionRecord record) {
        String sql = "INSERT INTO " + type.tableName()
                + " (user_id, username, session_id, question, answer, app_id, prompt_tokens, completion_tokens,"
                + " total_tokens, cost_quota, status, error_msg, duration_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql,
                record.getUserId(),
                record.getUsername(),
                record.getSessionId(),
                record.getQuestion(),
                record.getAnswer(),
                record.getAppId(),
                record.getPromptTokens(),
                record.getCompletionTokens(),
                record.getTotalTokens(),
                record.getCostQuota(),
                record.getStatus(),
                record.getErrorMsg(),
                record.getDurationMs());
    }

    public List<QuestionRecord> page(RecordType type, long userId, String keyword, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(type.tableName())
                .append(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        appendKeyword(sql, params, keyword);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public long count(RecordType type, long userId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ").append(type.tableName())
                .append(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        appendKeyword(sql, params, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 统计某用户在 [from, to) 时间段内"真正消耗过次数"的提问数。
     *
     * <p>只算 SUCCESS:被拦截(REJECTED)和上游失败(FAILED,已退还)不占用每日上限。
     * 走 idx_*_user_created 联合索引。</p>
     */
    public long countSuccessBetween(RecordType type, long userId, LocalDateTime from, LocalDateTime to) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + type.tableName()
                        + " WHERE user_id = ? AND created_at >= ? AND created_at < ? AND status = 'SUCCESS'",
                Long.class, userId, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return total == null ? 0L : total;
    }

    /**
     * 统计<b>某个用户在两条链路上合计</b>于 [from, to) 内成功消耗的次数。
     *
     * <p>给"每账号每日总量"用(见 {@code DailyLimitService})。口径与
     * {@link #countSuccessBetween} 完全一致 —— 同样只算 SUCCESS,所以被拦截和上游失败的
     * 请求不占名额。</p>
     *
     * <p>SQL 由 {@link RecordType} 拼出来(表名来自枚举,不含外部输入),再包一层
     * {@code UNION ALL} 用一条语句查完两张表 —— 这个计数每次提问都会跑一遍,
     * 少一次往返是值得的。加新链路时这里不用改。</p>
     */
    public long countSuccessAllChannelsBetween(long userId, LocalDateTime from, LocalDateTime to) {
        StringBuilder union = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (RecordType type : RecordType.values()) {
            if (union.length() > 0) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT 1 FROM ").append(type.tableName())
                    .append(" WHERE user_id = ? AND created_at >= ? AND created_at < ? AND status = 'SUCCESS'");
            params.add(userId);
            params.add(Timestamp.valueOf(from));
            params.add(Timestamp.valueOf(to));
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM (" + union + ") t", Long.class, params.toArray());
        return total == null ? 0L : total;
    }

    private void appendKeyword(StringBuilder sql, List<Object> params, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND question LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
    }
}
