package com.example.airag.record;

import java.time.LocalDateTime;

/**
 * 使用日志的一行:两张提问记录表合并后的统一视图。
 *
 * @param channel  链路:faq / after-sale
 * @param userId   提问用户 ID
 * @param username 提问账号 —— 记录表里冗余存了一份,所以管理后台看全站记录时不用再回表查账号
 */
public record LogEntry(
        String channel,
        long id,
        long userId,
        String username,
        String sessionId,
        String question,
        String answer,
        String status,
        String errorMsg,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int costQuota,
        long durationMs,
        LocalDateTime createdAt) {
}
