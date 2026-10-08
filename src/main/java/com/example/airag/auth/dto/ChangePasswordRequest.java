package com.example.airag.auth.dto;

/**
 * 自助修改密码请求体。
 *
 * <p>刻意把 {@code username} 也放在请求里:登录页上的"修改密码"入口是<b>在登录之前</b>用的,
 * 那时候没有令牌可以证明身份,只能靠"知道原密码"来证明。所以身份三要素一次给全。</p>
 *
 * <p>对已登录的用户来说多传一次账号也只是冗余,后端不会拿它去越权别人的账号 ——
 * 原密码对不上就一律 401。</p>
 */
public class ChangePasswordRequest {

    private String username;
    private String oldPassword;
    private String newPassword;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
