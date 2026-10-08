package com.example.airag.gateway;

/**
 * FastGPT 返回的 token 消耗(字段名与 OpenAI 兼容)。
 */
public record ChatUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static ChatUsage empty() {
        return new ChatUsage(0, 0, 0);
    }
}
