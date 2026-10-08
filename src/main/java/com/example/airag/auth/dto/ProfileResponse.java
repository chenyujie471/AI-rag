package com.example.airag.auth.dto;

import java.util.List;

import com.example.airag.quota.DailyLimitService;
import com.example.airag.quota.QuotaInfo;

/**
 * 当前登录用户信息 + 总额度 + 各知识库链路今日用量。
 */
public record ProfileResponse(UserProfile user, QuotaInfo quota, List<DailyLimitService.DailyUsage> daily,
                              String redirectUrl) {
}
