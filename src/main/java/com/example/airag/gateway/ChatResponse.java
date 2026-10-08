package com.example.airag.gateway;

import com.example.airag.quota.QuotaInfo;

/**
 * 非流式模式下的返回体。
 */
public record ChatResponse(String answer, ChatUsage usage, QuotaInfo quota) {
}
