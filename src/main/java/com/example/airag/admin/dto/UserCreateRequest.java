package com.example.airag.admin.dto;

/**
 * 新建用户请求体。
 *
 * <p>和本工程其它请求 DTO 一样用普通类 + setter,不依赖编译时 {@code -parameters} 参数。</p>
 *
 * <p>三个可空字段的约定:{@code null} = 不单独设置(每日上限则跟随全局配置),{@code -1} = 不限。</p>
 */
public class UserCreateRequest {

    private String username;
    private String password;
    private String nickname;
    /** ADMIN / USER,不填默认 USER */
    private String role;
    /** 总次数上限,-1 = 不限;不填用 app.quota.default-total。已退役,一般不用传 */
    private Integer quotaTotal;
    /** 每日总量(两条链路合计),不填=跟随全局 */
    private Integer dailyLimitTotal;
    private Integer dailyLimitFaq;
    private Integer dailyLimitAfterSale;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

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

    public Integer getQuotaTotal() {
        return quotaTotal;
    }

    public void setQuotaTotal(Integer quotaTotal) {
        this.quotaTotal = quotaTotal;
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
}
