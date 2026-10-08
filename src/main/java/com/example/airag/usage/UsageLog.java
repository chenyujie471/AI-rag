package com.example.airag.usage;

import java.time.LocalDateTime;

/**
 * 一条账号使用记录。
 *
 * <p>刻意<b>不含</b>用户问题与 AI 回答:那是 {@code ai_question_record} /
 * {@code after_sale_question_record} 两张内容表的职责。这张表只回答
 * "谁、什么时候、走哪条链路、扣了几次、成功没有、从哪个 IP 来的"。</p>
 *
 * <p>FastGPT 工作流内部的调用过程(节点流转、各模块耗时)也不在这里 ——
 * 网关调用上游时传的是 {@code detail=false},只解析回答文本与 usage。</p>
 */
public class UsageLog {

    private Long id;
    private long userId;
    private String username;
    private String channel;
    private String sessionId;
    private String appId;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private int costQuota;
    private String status;
    private String errorMsg;
    private long durationMs;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    /** 链路标识:faq / after-sale。 */
    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getCostQuota() {
        return costQuota;
    }

    public void setCostQuota(int costQuota) {
        this.costQuota = costQuota;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
