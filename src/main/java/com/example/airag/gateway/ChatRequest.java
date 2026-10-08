package com.example.airag.gateway;

/**
 * 前端发起的提问请求体。
 */
public class ChatRequest {

    private String question;
    /** 会话 ID;不传则由服务端生成。服务端会强制按用户做命名空间隔离。 */
    private String chatId;
    /** 是否要求流式返回,默认 true。TOKEN 扣减模式下会被强制关闭。 */
    private boolean stream = true;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }
}
