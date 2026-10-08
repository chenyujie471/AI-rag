package com.example.airag.user;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * sys_user 数据访问。
 */
@Repository
public class UserRepository {

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setNickname(rs.getString("nickname"));
        user.setRole(UserRole.of(rs.getString("role")));
        user.setStatus(rs.getInt("status"));
        user.setQuotaTotal(rs.getInt("quota_total"));
        user.setQuotaUsed(rs.getInt("quota_used"));
        // 这几列可以为 NULL(表示"跟随全局配置"),getInt 会把 NULL 读成 0,所以要看 wasNull
        int dailyTotal = rs.getInt("daily_limit_total");
        user.setDailyLimitTotal(rs.wasNull() ? null : dailyTotal);
        int dailyFaq = rs.getInt("daily_limit_faq");
        user.setDailyLimitFaq(rs.wasNull() ? null : dailyFaq);
        int dailyAfterSale = rs.getInt("daily_limit_after_sale");
        user.setDailyLimitAfterSale(rs.wasNull() ? null : dailyAfterSale);
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        user.setLastLoginAt(lastLogin == null ? null : lastLogin.toLocalDateTime());
        Timestamp createdAt = rs.getTimestamp("created_at");
        user.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        user.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return user;
    };

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(long id) {
        List<User> list = jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE username = ?", ROW_MAPPER, username);
        return list.stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Long.class);
        return count == null ? 0L : count;
    }

    /** 全量账号,给启动自检用(规模是"用户数",不是"记录数",不必分页)。 */
    public List<User> findAll() {
        return jdbcTemplate.query("SELECT * FROM sys_user ORDER BY id", ROW_MAPPER);
    }

    /** 新增用户,返回自增主键。 */
    public long insert(User user) {
        String sql = "INSERT INTO sys_user (username, password_hash, nickname, role, status, quota_total,"
                + " quota_used, daily_limit_total, daily_limit_faq, daily_limit_after_sale)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[] {"id"});
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getNickname());
            ps.setString(4, user.getRole().name());
            ps.setInt(5, user.getStatus());
            ps.setInt(6, user.getQuotaTotal());
            ps.setInt(7, user.getQuotaUsed());
            setNullableInt(ps, 8, user.getDailyLimitTotal());
            setNullableInt(ps, 9, user.getDailyLimitFaq());
            setNullableInt(ps, 10, user.getDailyLimitAfterSale());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建用户失败:未取得自增主键");
        }
        long id = key.longValue();
        user.setId(id);
        return id;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    public int updatePasswordHash(long userId, String passwordHash) {
        return jdbcTemplate.update("UPDATE sys_user SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    /** 调整某人的总次数(后续"配置次数"可以直接改这里对应的字段)。 */
    public int updateQuotaTotal(long userId, int quotaTotal) {
        return jdbcTemplate.update("UPDATE sys_user SET quota_total = ? WHERE id = ?", quotaTotal, userId);
    }

    public int resetQuotaUsed(long userId) {
        return jdbcTemplate.update("UPDATE sys_user SET quota_used = 0 WHERE id = ?", userId);
    }

    public void touchLastLogin(long userId) {
        jdbcTemplate.update("UPDATE sys_user SET last_login_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), userId);
    }

    /**
     * 原子扣减次数:只有在"账号启用"且"余额足够(或不限次)"时才会更新成功。
     *
     * @return 1=扣减成功,0=额度不足或账号被禁用
     */
    public int tryConsumeQuota(long userId, int amount) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET quota_used = quota_used + ? "
                        + "WHERE id = ? AND status = 1 "
                        + "AND (quota_total < 0 OR quota_used + ? <= quota_total)",
                amount, userId, amount);
    }

    /** 退还次数(上游调用失败时补偿)。 */
    public int refundQuota(long userId, int amount) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET quota_used = GREATEST(quota_used - ?, 0) WHERE id = ?",
                amount, userId);
    }

    // --------------------------------------------------------------- 管理后台

    /** 管理后台的用户列表,支持按账号/昵称模糊搜、按角色筛。 */
    public List<User> page(String keyword, UserRole role, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(params, keyword, role);
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM sys_user" + where + " ORDER BY id ASC LIMIT ? OFFSET ?",
                ROW_MAPPER, params.toArray());
    }

    public long count(String keyword, UserRole role) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(params, keyword, role);
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user" + where, Long.class, params.toArray());
        return value == null ? 0L : value;
    }

    /** 还剩几个管理员 —— 用来拦住"把最后一个管理员降级/删掉/禁用"这种会把自己锁在门外的操作。 */
    public long countEnabledAdmins() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE role = 'ADMIN' AND status = 1", Long.class);
        return value == null ? 0L : value;
    }

    public long countDisabled() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user WHERE status <> 1", Long.class);
        return value == null ? 0L : value;
    }

    /** 把还没有角色的老数据补成指定角色(只动 role 不是 ADMIN 的行)。 */
    public int promoteToAdmin(long userId) {
        return jdbcTemplate.update("UPDATE sys_user SET role = 'ADMIN' WHERE id = ? AND role <> 'ADMIN'", userId);
    }

    public int updateRole(long userId, UserRole role) {
        return jdbcTemplate.update("UPDATE sys_user SET role = ? WHERE id = ?", role.name(), userId);
    }

    public int updateStatus(long userId, int status) {
        return jdbcTemplate.update("UPDATE sys_user SET status = ? WHERE id = ?", status, userId);
    }

    public int updateNickname(long userId, String nickname) {
        return jdbcTemplate.update("UPDATE sys_user SET nickname = ? WHERE id = ?", nickname, userId);
    }

    /** 传 null 表示"跟随全局配置";整体覆盖,三个字段一起改。 */
    public int updateDailyLimits(long userId, Integer total, Integer faq, Integer afterSale) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET daily_limit_total = ?, daily_limit_faq = ?,"
                        + " daily_limit_after_sale = ? WHERE id = ?",
                new Object[] {total, faq, afterSale, userId},
                new int[] {java.sql.Types.INTEGER, java.sql.Types.INTEGER,
                        java.sql.Types.INTEGER, java.sql.Types.BIGINT});
    }

    /**
     * 把所有人还剩着的终身额度改成"不限"。
     *
     * <p>终身额度已经退役(见 {@code LifetimeQuotaCleanup}),但老库里的行还是旧值
     * (比如 100),照样会在 {@code QuotaService.tryConsume} 那里把人拦下来。
     * 这里一次性抹平,只动 {@code quota_total <> -1} 的行,幂等。</p>
     *
     * @return 影响行数,0 表示本来就都是不限
     */
    public int resetAllQuotaTotalsToUnlimited() {
        return jdbcTemplate.update("UPDATE sys_user SET quota_total = -1 WHERE quota_total <> -1");
    }

    /**
     * 删除用户。
     *
     * <p>只删 {@code sys_user} 这一行 —— 问答记录与使用日志<b>刻意保留</b>:
     * 它们是审计数据,人走了记录还得在,不然"上个月谁用了多少次"就查不出来了。</p>
     */
    public int deleteById(long userId) {
        return jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    private static String buildWhere(List<Object> params, String keyword, UserRole role) {
        StringBuilder where = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            where.append(where.isEmpty() ? " WHERE (" : " AND (");
            where.append("username LIKE ? OR nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("role = ?");
            params.add(role.name());
        }
        return where.toString();
    }
}
