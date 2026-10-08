package com.example.airag;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.example.airag.common.BizException;
import com.example.airag.quota.DailyLimitService;
import com.example.airag.record.RecordType;
import com.example.airag.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「每个账号每天的总次数上限」:两条链路合计、按账号独立、北京时间 0 点重置。
 *
 * <p>把"每条链路各自的每日上限"全关掉({@code faq=-1}、{@code after-sale=-1}),
 * 这样测的就只有总量这一层,两层限制不会互相掩盖。</p>
 *
 * <p>记录直接用 JDBC 插、并且<b>显式写 created_at</b> —— 不依赖数据库的
 * {@code CURRENT_TIMESTAMP},否则测试结果会随运行环境的时区变化而飘。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:airag_peruser;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.quota.daily-limit.per-user=3",
        "app.quota.daily-limit.faq=-1",
        "app.quota.daily-limit.after-sale=-1",
        "app.quota.daily-limit.zone=Asia/Shanghai"
})
class PerUserDailyLimitIntegrationTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Autowired
    DailyLimitService dailyLimitService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    UserRepository userRepository;

    /** 核心:两条链路加起来算 3 次就满了,不是每条链路各 3 次。 */
    @Test
    void countsBothChannelsTogether() {
        insert(RecordType.AI_QUESTION, 101, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, 101, "SUCCESS", now());
        insert(RecordType.AFTER_SALE, 101, "SUCCESS", now());

        assertEquals(3, dailyLimitService.usedTodayTotal(101), "两条链路应该合计");
        assertEquals(0, dailyLimitService.totalUsage(101).remaining());

        // 售后这条链路自己的上限是 -1(不限),但仍然会被总量拦住
        assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AFTER_SALE, 101));
        assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AI_QUESTION, 101));
    }

    /** 没满的时候能正常拿到名额,而且用完(close)之后名额要还回去。 */
    @Test
    void releasesPermitAfterUse() {
        insert(RecordType.AI_QUESTION, 102, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, 102, "SUCCESS", now());

        try (DailyLimitService.Permit permit = dailyLimitService.acquire(RecordType.AI_QUESTION, 102)) {
            assertTrue(permit != null);
            // 名额被自己占着:已成功 2 + 处理中 1 = 3,刚好到上限,所以第二次进不来
            assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AI_QUESTION, 102));
        }
        // 释放之后又能进了
        dailyLimitService.acquire(RecordType.AI_QUESTION, 102).close();
    }

    /** 按账号独立:101 满了不代表 103 也满了。 */
    @Test
    void limitIsPerAccountNotGlobal() {
        insert(RecordType.AI_QUESTION, 201, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, 201, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, 201, "SUCCESS", now());

        assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AI_QUESTION, 201));
        assertEquals(0, dailyLimitService.usedTodayTotal(202), "别的账号应该是干净的");
        dailyLimitService.acquire(RecordType.AI_QUESTION, 202).close();
    }

    /** 只有成功的不占名额:失败和被拦截都不算。 */
    @Test
    void failedAndRejectedDoNotCount() {
        insert(RecordType.AI_QUESTION, 301, "FAILED", now());
        insert(RecordType.AI_QUESTION, 301, "REJECTED", now());
        insert(RecordType.AI_QUESTION, 301, "SUCCESS", now());

        assertEquals(1, dailyLimitService.usedTodayTotal(301));
        dailyLimitService.acquire(RecordType.AI_QUESTION, 301).close();
    }

    /**
     * 日切边界:当天 00:00:00 算今天,前一天 23:59:59.999 不算。
     *
     * <p>这是"北京时间 0 点重置"的可验证形式 —— 边界由
     * {@code app.quota.daily-limit.zone} 决定,而不是 JVM 默认时区。</p>
     */
    @Test
    void dayBoundaryIsMidnightInConfiguredZone() {
        LocalDateTime midnight = LocalDate.now(BEIJING).atStartOfDay();
        insert(RecordType.AI_QUESTION, 401, "SUCCESS", midnight);                       // 算今天
        insert(RecordType.AI_QUESTION, 401, "SUCCESS", midnight.minusNanos(1_000_000)); // 不算

        assertEquals(1, dailyLimitService.usedTodayTotal(401), "只应统计当天 00:00 之后的");
    }

    /** 昨天的记录不占今天的名额,所以总量不拦截。 */
    @Test
    void yesterdayDoesNotConsumeToday() {
        for (int i = 0; i < 5; i++) {
            insert(RecordType.AI_QUESTION, 501, "SUCCESS", now().minusDays(1));
        }
        assertEquals(0, dailyLimitService.usedTodayTotal(501));
        dailyLimitService.acquire(RecordType.AI_QUESTION, 501).close();
    }

    /** 今日用量列表里应该多出"总计"这一行,方便前端展示。 */
    @Test
    void usageListContainsTotalRow() {
        insert(RecordType.AI_QUESTION, 601, "SUCCESS", now());

        var list = dailyLimitService.usageAll(601);
        assertEquals(3, list.size(), "两条链路 + 一条总计:" + list);

        DailyLimitService.DailyUsage total = list.stream()
                .filter(item -> DailyLimitService.TOTAL_CHANNEL.equals(item.channel()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有总计那一行: " + list));
        assertEquals(3, total.limit(), "上限应来自 app.quota.daily-limit.per-user");
        assertEquals(1, total.used());
        assertEquals(2, total.remaining());
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 管理员给某个账号单独设了每日总量 → 覆盖全局默认,而且<b>立刻生效</b>。
     *
     * <p>这是这条链路的重点:以前"每日上限"那个入口只写得动每链路的上限,
     * 而页面读的是全局默认,所以改完毫无反应。现在两者是同一个数了。</p>
     */
    @Test
    void personalTotalOverridesGlobalDefault() {
        long id = insertUser("limit_override");
        insert(RecordType.AI_QUESTION, id, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, id, "SUCCESS", now());
        insert(RecordType.AI_QUESTION, id, "SUCCESS", now());

        // 全局默认是 3(见类上的 @TestPropertySource),已经用满
        assertEquals(3, dailyLimitService.perUserLimit(id));
        assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AI_QUESTION, id));

        // 管理员改成 10 → 立刻能继续问,页面上的"今日总计"上限也跟着变
        jdbcTemplate.update("UPDATE sys_user SET daily_limit_total = 10 WHERE id = ?", id);
        assertEquals(10, dailyLimitService.perUserLimit(id));
        assertEquals(10, dailyLimitService.totalUsage(id).limit());
        assertEquals(7, dailyLimitService.totalUsage(id).remaining());
        dailyLimitService.acquire(RecordType.AI_QUESTION, id).close();

        // 改成 0 表示"这个账号今天一次都不许问"
        jdbcTemplate.update("UPDATE sys_user SET daily_limit_total = 0 WHERE id = ?", id);
        assertEquals(0, dailyLimitService.perUserLimit(id));
        assertThrows(BizException.class, () -> dailyLimitService.acquire(RecordType.AI_QUESTION, id));

        // 设成 -1 则是放开
        jdbcTemplate.update("UPDATE sys_user SET daily_limit_total = -1 WHERE id = ?", id);
        assertEquals(-1, dailyLimitService.perUserLimit(id));
        assertTrue(dailyLimitService.totalUsage(id).unlimited());
        dailyLimitService.acquire(RecordType.AI_QUESTION, id).close();
    }

    /** 终身额度退场:老库里残留的正数会被抹成 -1,不然它还会在 QuotaService 那里拦人。 */
    @Test
    void retiringLifetimeQuotaZeroesLegacyRows() {
        long id = insertUser("legacy_quota");
        jdbcTemplate.update("UPDATE sys_user SET quota_total = 100 WHERE id = ?", id);

        userRepository.resetAllQuotaTotalsToUnlimited();

        assertEquals(-1, jdbcTemplate.queryForObject(
                "SELECT quota_total FROM sys_user WHERE id = ?", Integer.class, id));
    }

    /** 插一个只有账号密码的 sys_user 行,拿到自增主键。其余列都有默认值。 */
    private long insertUser(String username) {
        jdbcTemplate.update("INSERT INTO sys_user (username, password_hash, nickname) VALUES (?, ?, ?)",
                username, "not-a-real-hash", username);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(BEIJING);
    }

    private void insert(RecordType type, long userId, String status, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO " + type.tableName()
                        + " (user_id, username, question, answer, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "u" + userId, "问题", "回答", status, Timestamp.valueOf(createdAt));
    }
}
