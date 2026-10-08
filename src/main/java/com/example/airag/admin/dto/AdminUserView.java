package com.example.airag.admin.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.example.airag.user.User;

/**
 * 管理后台"用户列表"里的一行。
 *
 * <p>把列表页要显示的东西一次给全,避免前端拿到用户列表后再为每个人各请求一次用量
 * (N+1 请求)。今日用量由调用方用<b>一条</b>分组查询算好后传进来。</p>
 *
 * @param dailyLimitTotal      个人每日总量(两条链路合计):null=跟随全局,-1=不限。这是主限制
 * @param dailyLimitFaq        个人每日上限:null=跟随全局,-1=不限
 * @param dailyLimitAfterSale  同上
 * @param todayFaq             今日已成功提问次数
 * @param todayAfterSale       同上
 */
public record AdminUserView(
        long id,
        String username,
        String nickname,
        String role,
        int status,
        int quotaTotal,
        int quotaUsed,
        Integer dailyLimitTotal,
        Integer dailyLimitFaq,
        Integer dailyLimitAfterSale,
        long todayFaq,
        long todayAfterSale,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {

    public static AdminUserView of(User user, Map<String, Long> todayUsage) {
        Map<String, Long> today = todayUsage == null ? Map.of() : todayUsage;
        return new AdminUserView(
                user.getId() == null ? 0L : user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole().name(),
                user.getStatus(),
                user.getQuotaTotal(),
                user.getQuotaUsed(),
                user.getDailyLimitTotal(),
                user.getDailyLimitFaq(),
                user.getDailyLimitAfterSale(),
                today.getOrDefault("faq", 0L),
                today.getOrDefault("after-sale", 0L),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
