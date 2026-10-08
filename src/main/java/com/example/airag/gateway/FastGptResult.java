package com.example.airag.gateway;

/**
 * 一次上游调用的结果。
 *
 * @param success         是否成功拿到回答
 * @param clientCommitted 是否已经把内容写给了浏览器(流式已经开始)。为 true 时不能再写 JSON 错误体
 * @param answer          回答内容(流式时是拼接结果)
 * @param usage           token 消耗
 * @param upstreamStatus  上游 HTTP 状态码
 * @param errorMessage    失败原因
 */
public record FastGptResult(boolean success, boolean clientCommitted, String answer, ChatUsage usage,
                            int upstreamStatus, String errorMessage) {

    public static FastGptResult ok(String answer, ChatUsage usage, boolean clientCommitted) {
        return new FastGptResult(true, clientCommitted, answer == null ? "" : answer,
                usage == null ? ChatUsage.empty() : usage, 200, null);
    }

    public static FastGptResult fail(int upstreamStatus, String errorMessage, boolean clientCommitted) {
        return new FastGptResult(false, clientCommitted, "", ChatUsage.empty(), upstreamStatus, errorMessage);
    }
}
