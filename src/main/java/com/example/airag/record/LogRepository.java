package com.example.airag.record;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 使用日志查询:把 ai_question_record 与 after_sale_question_record 合并成一个时间线。
 *
 * <p>表名、链路名都来自 {@link RecordType} 枚举,外部输入只走占位符参数。
 * 普通用户查自己的数据时带 user_id 条件,走 idx_*_user_created 索引;
 * 管理后台查全站时 user_id 传 null,此时不带该条件,走 idx_*_created。</p>
 *
 * <p><b>越权防线在调用方,不在 SQL。</b>{@link LogController} 永远传入当前登录用户的 id,
 * 只有 {@link com.example.airag.admin.AdminLogController} 才会传 null —— 后者本身被
 * {@link com.example.airag.auth.AdminInterceptor} 挡着。所以这里不内置权限判断,
 * 但每个方法都要求显式传 userId,避免"忘了加条件"变成默认放开。</p>
 */
@Repository
public class LogRepository {

    private static final String COLUMNS = "id, user_id, username, session_id, question, answer, status, error_msg,"
            + " prompt_tokens, completion_tokens, total_tokens, cost_quota, duration_ms, created_at";

    private static final RowMapper<LogEntry> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new LogEntry(
                rs.getString("channel").trim(),
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("session_id"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getString("status"),
                rs.getString("error_msg"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getInt("total_tokens"),
                rs.getInt("cost_quota"),
                rs.getLong("duration_ms"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    private final JdbcTemplate jdbcTemplate;

    public LogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某个用户自己的日志。 */
    public List<LogEntry> page(long userId, LogQuery query, int offset, int limit) {
        return page(Long.valueOf(userId), null, query, offset, limit);
    }

    /** 某个用户自己的日志条数。 */
    public long count(long userId, LogQuery query) {
        return count(Long.valueOf(userId), null, query);
    }

    /**
     * 通用分页查询。
     *
     * @param userId   null = 不限用户(管理后台看全部)
     * @param username 非空时按账号模糊匹配(冗余列,便于按人筛)
     */
    public List<LogEntry> page(Long userId, String username, LogQuery query, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder union = new StringBuilder();
        for (RecordType type : typesOf(query)) {
            if (!union.isEmpty()) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT '").append(type.pathKey()).append("' AS channel, ").append(COLUMNS)
                    .append(" FROM ").append(type.tableName());
            appendWhere(union, params, userId, username, query);
        }
        String sql = "SELECT * FROM (" + union + ") t ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql, ROW_MAPPER, params.toArray());
    }

    public long count(Long userId, String username, LogQuery query) {
        long total = 0;
        for (RecordType type : typesOf(query)) {
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ").append(type.tableName());
            appendWhere(sql, params, userId, username, query);
            Long value = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
            total += value == null ? 0 : value;
        }
        return total;
    }

    /** 某条链路按状态分组的次数与 token 合计。userId 传 null 表示全站。 */
    public List<StatusCount> countByStatus(RecordType type, Long userId) {
        return countByStatusBetween(type, userId, null, null);
    }

    /** 兼容旧签名(单用户)。 */
    public List<StatusCount> countByStatus(RecordType type, long userId) {
        return countByStatus(type, Long.valueOf(userId));
    }

    /**
     * 按状态分组统计,可限定时间区间与用户。
     *
     * @param userId null = 全站
     * @param from   null = 不限起点
     * @param to     null = 不限终点
     */
    public List<StatusCount> countByStatusBetween(RecordType type, Long userId,
                                                 LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder(
                "SELECT status, COUNT(1) AS cnt, COALESCE(SUM(total_tokens), 0) AS tokens FROM ")
                .append(type.tableName()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.valueOf(to));
        }
        sql.append(" GROUP BY status");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("cnt"), rs.getLong("tokens")),
                params.toArray());
    }

    /**
     * 全站按"用户 + 链路"汇总 [from, to) 区间内的成功次数。
     *
     * <p>一次查询拿到所有人当天的用量,避免管理后台的用户列表对每一行各查一次
     * (N+1 查询)。返回的 map 是 {@code userId -> (channel -> 次数)}。</p>
     */
    public Map<Long, Map<String, Long>> successCountByUser(LocalDateTime from, LocalDateTime to) {
        StringBuilder union = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (RecordType type : RecordType.values()) {
            if (!union.isEmpty()) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT user_id, '").append(type.pathKey()).append("' AS channel FROM ")
                    .append(type.tableName()).append(" WHERE status = 'SUCCESS' AND created_at >= ? AND created_at < ?");
            params.add(Timestamp.valueOf(from));
            params.add(Timestamp.valueOf(to));
        }
        String sql = "SELECT user_id, channel, COUNT(1) AS cnt FROM (" + union + ") t GROUP BY user_id, channel";
        Map<Long, Map<String, Long>> result = new LinkedHashMap<>();
        // 这里借用 RowMapper 逐行累积:它的返回值用不上,只要副作用
        jdbcTemplate.query(sql, (rs, rowNum) -> {
            result.computeIfAbsent(rs.getLong("user_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("channel"), rs.getLong("cnt"));
            return null;
        }, params.toArray());
        return result;
    }

    private static List<RecordType> typesOf(LogQuery query) {
        return query.type() == null ? List.of(RecordType.values()) : List.of(query.type());
    }

    private static void appendWhere(StringBuilder sql, List<Object> params, Long userId, String username,
                                    LogQuery query) {
        // 用 1=1 打底,后面所有条件都能无脑 AND 上去,省掉一堆"要不要加 WHERE"的判断
        sql.append(" WHERE 1 = 1");
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (username != null && !username.isBlank()) {
            sql.append(" AND username LIKE ?");
            params.add("%" + username.trim() + "%");
        }
        if (query.start() != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.valueOf(query.start()));
        }
        if (query.end() != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.valueOf(query.end()));
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            params.add(query.status());
        }
        if (query.keyword() != null) {
            sql.append(" AND (question LIKE ? OR answer LIKE ?)");
            String like = "%" + query.keyword() + "%";
            params.add(like);
            params.add(like);
        }
    }

    public record StatusCount(String status, long count, long tokens) {
    }
}
