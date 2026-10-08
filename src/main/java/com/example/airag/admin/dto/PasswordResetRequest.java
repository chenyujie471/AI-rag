package com.example.airag.admin.dto;

/** 管理员重置某人密码的请求体。 */
public class PasswordResetRequest {

    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
