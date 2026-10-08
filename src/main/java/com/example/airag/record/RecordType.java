package com.example.airag.record;

import com.example.airag.common.BizException;

/**
 * 两条知识库链路对应的记录表。
 *
 * <p>表名只从枚举常量里取,不接受外部输入,因此拼接 SQL 不存在注入风险。</p>
 */
public enum RecordType {

    /** 平台 FAQ 链路 -> ai_question_record */
    AI_QUESTION("ai_question_record", "faq"),

    /** 售后知识库链路 -> after_sale_question_record */
    AFTER_SALE("after_sale_question_record", "after-sale");

    private final String tableName;
    private final String pathKey;

    RecordType(String tableName, String pathKey) {
        this.tableName = tableName;
        this.pathKey = pathKey;
    }

    public String tableName() {
        return tableName;
    }

    /** 对外 URL 中使用的名字。 */
    public String pathKey() {
        return pathKey;
    }

    /** 支持 faq / after-sale(也兼容枚举名 AI_QUESTION / AFTER_SALE)。 */
    public static RecordType fromPathKey(String key) {
        if (key != null) {
            for (RecordType type : values()) {
                if (type.pathKey.equalsIgnoreCase(key) || type.name().equalsIgnoreCase(key)) {
                    return type;
                }
            }
        }
        throw BizException.badRequest("未知的记录类型: " + key + ",可选值 faq / after-sale");
    }
}
