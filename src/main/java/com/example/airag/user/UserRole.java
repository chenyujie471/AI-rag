package com.example.airag.user;

/**
 * 用户角色。目前只有两档,够用就好。
 *
 * <p>权限判断<b>不走令牌</b>,而是每次请求按 {@code user_id} 回查一次 {@code sys_user.role}。
 * 这样管理员把某人的权限撤掉之后立刻生效,不用等他的令牌过期 —— 令牌里带角色的做法做不到
 * 这一点,而"撤销了权限但对方还能用半小时"在管理后台这种场景里是不能接受的。</p>
 */
public enum UserRole {

    /** 普通用户:只能用自己的两条问答链路和看自己的日志 */
    USER,

    /** 管理员:可以进管理后台,查看所有人的记录、管理账号与额度 */
    ADMIN;

    /** 宽松解析:认不出来的一律当普通用户,避免脏数据把人意外提权。 */
    public static UserRole of(String value) {
        if (value == null) {
            return USER;
        }
        return "ADMIN".equalsIgnoreCase(value.trim()) ? ADMIN : USER;
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
