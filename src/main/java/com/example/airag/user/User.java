package com.example.airag.user;

import java.time.LocalDateTime;

/**
 * 系统用户(sys_user)。
 */
public class User {

    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    /** 角色,决定能否进入管理后台 */
    private UserRole role = UserRole.USER;
    /** 1=启用 0=禁用 */
    private int status;
    /** -1 表示不限次数。已退役:不再参与拦截,也不再在界面上展示(见 LifetimeQuotaCleanup) */
    private int quotaTotal;
    private int quotaUsed;
    /** 每日总量上限(两条链路合计):null=跟随全局配置,-1=不限。这是主限制 */
    private Integer dailyLimitTotal;
    /** 平台 FAQ 每日上限:null=跟随全局配置,-1=不限 */
    private Integer dailyLimitFaq;
    /** 售后每日上限:null=跟随全局配置,-1=不限 */
    private Integer dailyLimitAfterSale;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public UserRole getRole() {
        return role == null ? UserRole.USER : role;
    }

    public void setRole(UserRole role) {
        this.role = role == null ? UserRole.USER : role;
    }

    public boolean isAdmin() {
        return getRole().isAdmin();
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getQuotaTotal() {
        return quotaTotal;
    }

    public void setQuotaTotal(int quotaTotal) {
        this.quotaTotal = quotaTotal;
    }

    public int getQuotaUsed() {
        return quotaUsed;
    }

    public void setQuotaUsed(int quotaUsed) {
        this.quotaUsed = quotaUsed;
    }

    public Integer getDailyLimitTotal() {
        return dailyLimitTotal;
    }

    public void setDailyLimitTotal(Integer dailyLimitTotal) {
        this.dailyLimitTotal = dailyLimitTotal;
    }

    public Integer getDailyLimitFaq() {
        return dailyLimitFaq;
    }

    public void setDailyLimitFaq(Integer dailyLimitFaq) {
        this.dailyLimitFaq = dailyLimitFaq;
    }

    public Integer getDailyLimitAfterSale() {
        return dailyLimitAfterSale;
    }

    public void setDailyLimitAfterSale(Integer dailyLimitAfterSale) {
        this.dailyLimitAfterSale = dailyLimitAfterSale;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
