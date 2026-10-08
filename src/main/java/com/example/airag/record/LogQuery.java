package com.example.airag.record;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

import com.example.airag.common.BizException;

/**
 * 使用日志的筛选条件(已校验、已归一化)。
 *
 * @param type    null = 两条链路都查
 * @param status  null = 不限;否则 SUCCESS / FAILED / REJECTED
 * @param keyword null = 不限;匹配问题或回答
 * @param start   含
 * @param end     含
 */
public record LogQuery(RecordType type, String status, String keyword, LocalDateTime start, LocalDateTime end) {

    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILED", "REJECTED");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static LogQuery of(String type, String status, String keyword, String start, String end) {
        RecordType recordType = isBlank(type) || "all".equalsIgnoreCase(type.trim())
                ? null : RecordType.fromPathKey(type.trim());

        String normalizedStatus = null;
        if (!isBlank(status) && !"all".equalsIgnoreCase(status.trim())) {
            normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalizedStatus)) {
                throw BizException.badRequest("未知的状态: " + status + ",可选值 SUCCESS / FAILED / REJECTED");
            }
        }

        String normalizedKeyword = isBlank(keyword) ? null : keyword.trim();
        if (normalizedKeyword != null && normalizedKeyword.length() > 100) {
            normalizedKeyword = normalizedKeyword.substring(0, 100);
        }

        LocalDateTime from = parse(start, "开始时间");
        LocalDateTime to = parse(end, "结束时间");
        if (from != null && to != null && from.isAfter(to)) {
            throw BizException.badRequest("开始时间不能晚于结束时间");
        }
        return new LogQuery(recordType, normalizedStatus, normalizedKeyword, from, to);
    }

    /** 兼容 "2026-09-30 14:13:22"、"2026-09-30T14:13"(datetime-local)、"2026-09-30" 三种写法。 */
    static LocalDateTime parse(String value, String label) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim().replace('T', ' ');
        if (text.length() == 10) {
            text += " 00:00:00";
        } else if (text.length() == 16) {
            text += ":00";
        }
        try {
            return LocalDateTime.parse(text, FORMAT);
        } catch (DateTimeParseException ex) {
            throw BizException.badRequest(label + "格式不正确,应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
