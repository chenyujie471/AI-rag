package com.example.airag.admin.dto;

import java.util.List;

/**
 * 管理后台首页的概览数据。
 *
 * @param userCount     用户总数
 * @param adminCount    管理员数量
 * @param disabledCount 已禁用账号数量
 * @param today         今日各链路统计
 * @param allTime       历史累计各链路统计
 */
public record AdminOverview(
        long userCount,
        long adminCount,
        long disabledCount,
        List<ChannelStat> today,
        List<ChannelStat> allTime) {

    /**
     * 一条链路的统计。
     *
     * @param channel faq / after-sale
     * @param total   全部调用次数(含失败与被拦)
     */
    public record ChannelStat(String channel, long total, long success, long failed, long rejected, long tokens) {
    }
}
