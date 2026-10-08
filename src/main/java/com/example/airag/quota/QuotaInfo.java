package com.example.airag.quota;

/**
 * 某个用户的额度快照。
 *
 * @param unlimited true 表示不限次数(此时 remaining 无意义)
 * @param total     总额度,-1 表示不限
 * @param used      已用次数
 * @param remaining 剩余次数
 */
public record QuotaInfo(boolean unlimited, int total, int used, int remaining) {
}
