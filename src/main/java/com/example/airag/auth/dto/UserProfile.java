package com.example.airag.auth.dto;

import java.time.LocalDateTime;

import com.example.airag.user.User;

/**
 * 对外暴露的用户信息(绝不包含密码哈希)。
 *
 * @param role  ADMIN / USER
 * @param admin 是否管理员 —— 单独给一个布尔,前端不用去认 role 字符串。
 *              注意这只用于<b>决定要不要显示管理入口</b>,真正的权限校验在后端
 *              ({@code AdminInterceptor} 每次都会回查数据库),前端藏不藏按钮不影响安全。
 */
public record UserProfile(Long id, String username, String nickname, String role, boolean admin, int status,
                          LocalDateTime lastLoginAt, LocalDateTime createdAt) {

    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getUsername(), user.getNickname(),
                user.getRole().name(), user.isAdmin(), user.getStatus(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
