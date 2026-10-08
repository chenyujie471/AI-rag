package com.example.airag.record;

/**
 * 提问记录的最终状态。
 */
public enum RecordStatus {

    /** 正常拿到回答 */
    SUCCESS,
    /** 调用上游失败 */
    FAILED,
    /** 被网关拦截(额度不足 / 账号禁用等),没有真正转发给 FastGPT */
    REJECTED
}
