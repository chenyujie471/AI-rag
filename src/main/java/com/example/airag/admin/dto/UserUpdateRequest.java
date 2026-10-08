package com.example.airag.admin.dto;

/**
 * 修改用户请求体 —— <b>整体覆盖</b>,不是局部更新。
 *
 * <p>管理后台页面上本来就持有这个人的完整状态,整体覆盖比"局部更新"少一类歧义:
 * 局部更新里 {@code null} 既可能是"没传这个字段",也可能是"要把它清空",
 * 两者无法区分,而每日上限恰恰需要"清空成跟随全局"这个动作。</p>
 *
 * <p>约定:</p>
 * <ul>
 *   <li>{@code quotaTotal}:{@code null} = <b>保持原值不动</b>(它不能为空,没法用 null 表达"清空")</li>
 *   <li>{@code nickname} / {@code role} / {@code status}:{@code null} = 保持原值</li>
 * </ul>
 *
 * <p>每日上限<b>故意不在这里</b>,而是单独一个 {@code PUT /api/admin/users/{id}/daily-limit}。
 * 原因:每日上限需要"清空 → 跟随全局"这个动作,而它只能用 {@code null} 表达;
 * 如果和昵称之类混在一个整体覆盖的请求里,那么一次"只想改昵称"的调用就会顺手把
 * 每日上限清掉 —— 这种坑不值得为了少一个接口去踩。</p>
 */
public class UserUpdateRequest {

    private String nickname;
    /** ADMIN / USER;null = 保持原值 */
    private String role;
    /** 1=启用 0=禁用;null = 保持原值 */
    private Integer status;
    /** null = 保持原值 */
    private Integer quotaTotal;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getQuotaTotal() {
        return quotaTotal;
    }

    public void setQuotaTotal(Integer quotaTotal) {
        this.quotaTotal = quotaTotal;
    }
}
