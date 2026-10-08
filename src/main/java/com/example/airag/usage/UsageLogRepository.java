package com.example.airag.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * usage_log 的写入。
 *
 * <p>表名是固定的,不来自任何外部输入,因此拼接 SQL 不存在注入风险。</p>
 */
@Repository
public class UsageLogRepository {

    private static final String INSERT_SQL =
            "INSERT INTO usage_log (user_id, username, channel, session_id, app_id, prompt_tokens,"
                    + " completion_tokens, total_tokens, cost_quota, status, error_msg, duration_ms,"
                    + " client_ip, user_agent)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public UsageLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(UsageLog entry) {
        return jdbcTemplate.update(INSERT_SQL,
                entry.getUserId(),
                entry.getUsername(),
                entry.getChannel(),
                entry.getSessionId(),
                entry.getAppId(),
                entry.getPromptTokens(),
                entry.getCompletionTokens(),
                entry.getTotalTokens(),
                entry.getCostQuota(),
                entry.getStatus(),
                entry.getErrorMsg(),
                entry.getDurationMs(),
                entry.getClientIp(),
                entry.getUserAgent());
    }
}
