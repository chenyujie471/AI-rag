package com.example.airag.admin.dto;

/**
 * 设置某人的每日上限。
 *
 * <p>三个字段都是可空的,语义:<b>{@code null} = 跟随全局配置</b>,{@code -1} = 不限,
 * 大于等于 0 = 具体次数。整体覆盖,不做局部更新 —— 所以前端每次都要把三个字段一起提交,
 * 漏传的那个会被当成 null(也就是"跟随全局")。</p>
 *
 * @param total     每日总量(两条链路合计),<b>主限制</b>
 * @param faq       平台 FAQ 单独的上限
 * @param afterSale 售后单独的上限
 */
public class DailyLimitRequest {

    private Integer total;
    private Integer faq;
    private Integer afterSale;

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getFaq() {
        return faq;
    }

    public void setFaq(Integer faq) {
        this.faq = faq;
    }

    public Integer getAfterSale() {
        return afterSale;
    }

    public void setAfterSale(Integer afterSale) {
        this.afterSale = afterSale;
    }
}
