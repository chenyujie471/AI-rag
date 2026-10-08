package com.example.airag.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import com.example.airag.record.RecordRepository;
import com.example.airag.record.RecordType;
import com.example.airag.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 每个账号、每个自然日的提问次数上限,分两层。
 *
 * <ol>
 *   <li><b>每账号每日总量</b>:两条链路合计,这是<b>主限制</b> —— 比如"每个账号每天 20 次"。
 *       管理员在后台给某个人单独设过({@code sys_user.daily_limit_total})就用那个,
 *       没设过(为 null)才用全局默认 {@code app.quota.daily-limit.per-user}。</li>
 *   <li><b>单条链路的每人上限</b>:个人配置({@code sys_user.daily_limit_faq} /
 *       {@code daily_limit_after_sale},管理后台里按人设)优先,没配过则跟随全局
 *       ({@code app.quota.daily-limit.faq} / {@code .after-sale})。默认不限,
 *       用来在总量之上再单独收紧某条链路或某个人。</li>
 * </ol>
 *
 * <p>两层是「<b>两个都要满足</b>」的关系 —— 谁先到就按谁的话术拒绝。</p>
 *
 * <p>计数来源是两张记录表里当天 {@code status='SUCCESS'} 的条数,因此:</p>
 * <ul>
 *   <li>不需要额外建表,服务重启也不会丢计数;</li>
 *   <li>被拦截 / 上游失败(已退还)的请求不占用当日次数。</li>
 * </ul>
 *
 * <p>自然日的边界按 {@code app.quota.daily-limit.zone}(默认 {@code Asia/Shanghai})算,
 * 即<b>北京时间 0 点重置</b>,刻意不跟随 JVM 默认时区。</p>
 *
 * <p>"查数 → 转发 → 落库"之间有时间差,并发发起多个请求时,光查库会少算正在处理的那几个。
 * 这里用一个进程内的 in-flight 计数补上这段窗口:检查时按 "已成功 + 处理中" 判断。
 * 多实例部署时该补偿只在单机内生效(上限最多被并发多放行 实例数-1 次),如需严格,换成 Redis 计数即可。</p>
 */
@Service
public class DailyLimitService {

    private final RecordRepository recordRepository;
    private final UserRepository userRepository;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /**
     * "每账号每日总量"那一行在 {@link DailyUsage#channel} 里的取值。
     *
     * <p>它表示的是 {@code channel='faq'} 和 {@code 'after-sale'} 两条链路的合计,
     * 不是第三条链路。提问记录里不会有这个值,所以不会和真实的 channel 混淆;
     * 前端也按这个值取标签(见 assets/layout.js 的 CHANNELS)。</p>
     */
    public static final String TOTAL_CHANNEL = "total";

    /** 每日总量那层的 in-flight key。用户 id 是数字,不会和它撞。 */
    private static final String TOTAL_KEY_PREFIX = "total|";

    @Autowired
    public DailyLimitService(RecordRepository recordRepository, UserRepository userRepository,
                             AppProperties properties) {
        this(recordRepository, userRepository, properties, dailyClock(properties));
    }

    /**
     * "自然日"的时钟。<b>显式用配置里的时区</b>(默认 {@code Asia/Shanghai}),
     * 而不是 {@code Clock.systemDefaultZone()}。
     *
     * <p>原因:记录表里的 {@code created_at} 是 MySQL 的 {@code DEFAULT CURRENT_TIMESTAMP}
     * 生成的,存的是数据库本地时间;如果 JVM 跑在 UTC(容器里很常见)而这里用默认时区,
     * 日切边界就会落在北京时间早上 8 点,和"0 点刷新"的预期完全不一致。</p>
     */
    private static Clock dailyClock(AppProperties properties) {
        return Clock.system(ZoneId.of(properties.quota().dailyLimit().zone()));
    }

    DailyLimitService(RecordRepository recordRepository, UserRepository userRepository,
                      AppProperties properties, Clock clock) {
        this.recordRepository = recordRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 这个人在这条链路上的实际上限:<b>个人配置优先,没配就跟随全局</b>。小于 0 表示不限。
     *
     * <p>每次都回查一次 {@code sys_user} 而不是缓存 —— 管理员在后台把某人的上限从 5 改成 50,
     * 下一次提问就应该按 50 算。多一次主键查询换"改完立即生效",这个取舍是划算的。</p>
     */
    public int limitOf(RecordType type, long userId) {
        Integer personal = personalLimitOf(type, userId);
        return personal != null ? personal : globalLimitOf(type);
    }

    /** 管理员给这个人单独设的上限;null 表示没设过,跟随全局。 */
    public Integer personalLimitOf(RecordType type, long userId) {
        return userRepository.findById(userId)
                .map(user -> type == RecordType.AI_QUESTION
                        ? user.getDailyLimitFaq()
                        : user.getDailyLimitAfterSale())
                .orElse(null);
    }

    /** 全局默认上限(个人没配时生效),来自 application.yaml。 */
    public int globalLimitOf(RecordType type) {
        AppProperties.DailyLimit config = properties.quota().dailyLimit();
        return type == RecordType.AI_QUESTION ? config.faq() : config.afterSale();
    }

    /** 今日已成功次数(不含处理中)。 */
    public long usedToday(RecordType type, long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.atStartOfDay();
        return recordRepository.countSuccessBetween(type, userId, from, from.plusDays(1));
    }

    /** 某条链路今日用量快照。 */
    public DailyUsage usage(RecordType type, long userId) {
        int limit = limitOf(type, userId);
        long used = usedToday(type, userId);
        if (limit < 0) {
            return new DailyUsage(type.pathKey(), true, -1, used, -1);
        }
        return new DailyUsage(type.pathKey(), false, limit, used, Math.max(limit - used, 0));
    }

    /** 两条链路各自的今日用量,外加一条"今日总计"。 */
    public List<DailyUsage> usageAll(long userId) {
        List<DailyUsage> list = new ArrayList<>();
        for (RecordType type : RecordType.values()) {
            list.add(usage(type, userId));
        }
        list.add(totalUsage(userId));
        return list;
    }

    // ------------------------------------------------------------ 每账号每日总量 --

    /**
     * 这个账号每天的<b>总</b>次数上限(两条链路合计)。小于 0 表示不限。
     *
     * <p>取值优先级和管理后台的入口一致:<b>管理员给这个人单独设的
     * {@code sys_user.daily_limit_total} 优先</b>,没设过(为 null)才用全局默认
     * {@code app.quota.daily-limit.per-user}。每次都回查,改完立即生效。</p>
     *
     * <p>注意别和 {@link #globalLimitOf(RecordType)} 搞混:那个是"某条链路的<b>默认</b>每人上限"
     * (个人没在后台单独配时生效),这个是"这个账号一天最多问几次,不分链路"。</p>
     */
    public int perUserLimit(long userId) {
        Integer personal = personalTotalLimitOf(userId);
        return personal != null ? personal : properties.quota().dailyLimit().perUser();
    }

    /** 管理员给这个人单独设的每日总量;null 表示没设过,跟随全局。 */
    public Integer personalTotalLimitOf(long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getDailyLimitTotal())
                .orElse(null);
    }

    /** 某账号今日已成功次数(两条链路合计,不含处理中)。 */
    public long usedTodayTotal(long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.atStartOfDay();
        return recordRepository.countSuccessAllChannelsBetween(userId, from, from.plusDays(1));
    }

    /** 某账号今日总量快照。 */
    public DailyUsage totalUsage(long userId) {
        int limit = perUserLimit(userId);
        long used = usedTodayTotal(userId);
        if (limit < 0) {
            return new DailyUsage(TOTAL_CHANNEL, true, -1, used, -1);
        }
        return new DailyUsage(TOTAL_CHANNEL, false, limit, used, Math.max(limit - used, 0));
    }

    /**
     * 占用一个当日名额。要<b>同时</b>过两关:每账号每日总量,以及该链路的个人上限。
     *
     * <p>先查总量(这才是主限制,提示也更有用),再查链路;链路满了就把刚占的总量名额让回去,
     * 不能白占一个名额。</p>
     *
     * @throws BizException 当日次数已满(403,错误码 {@code DAILY_LIMIT_EXCEEDED})
     */
    public Permit acquire(RecordType type, long userId) {
        if (!properties.quota().enabled()) {
            return Permit.NOOP;
        }
        Permit total = acquireTotal(userId);
        Permit personal;
        try {
            personal = acquireChannel(type, userId);
        } catch (BizException exhausted) {
            total.close();
            throw exhausted;
        }
        return () -> {
            personal.close();
            total.close();
        };
    }

    /** 第一层:这个账号今天一共还能问几次(不分链路)。 */
    private Permit acquireTotal(long userId) {
        int limit = perUserLimit(userId);
        if (limit < 0) {
            return Permit.NOOP;
        }
        String key = TOTAL_KEY_PREFIX + userId;
        AtomicInteger counter = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        int pending = counter.incrementAndGet();
        long used = usedTodayTotal(userId);
        if (used + pending > limit) {
            release(key, counter);
            throw BizException.dailyLimitExceeded("今日提问次数已达上限(" + limit
                    + " 次,两条链路合计),请明天再来;北京时间 0 点重置");
        }
        return () -> release(key, counter);
    }

    /** 第二层:这条链路各自的每人上限(个人配置优先,没配就跟随全局)。 */
    private Permit acquireChannel(RecordType type, long userId) {
        int limit = limitOf(type, userId);
        if (limit < 0) {
            return Permit.NOOP;
        }
        String key = userId + "|" + type.name();
        AtomicInteger counter = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        int pending = counter.incrementAndGet();
        long used = usedToday(type, userId);
        if (used + pending > limit) {
            release(key, counter);
            throw BizException.dailyLimitExceeded(label(type) + "今日提问次数已达上限(" + limit
                    + " 次),请明天再来");
        }
        return () -> release(key, counter);
    }

    private void release(String key, AtomicInteger counter) {
        if (counter.decrementAndGet() <= 0) {
            inFlight.remove(key, counter);
        }
    }

    private static String label(RecordType type) {
        return type == RecordType.AI_QUESTION ? "平台 FAQ " : "售后咨询 ";
    }

    /** 当日名额凭证。 */
    @FunctionalInterface
    public interface Permit extends AutoCloseable {

        Permit NOOP = () -> {
        };

        @Override
        void close();
    }

    /**
     * @param channel   faq / after-sale,或 {@link #TOTAL_CHANNEL}(全站合计那一行)
     * @param unlimited 是否不限
     * @param limit     每日上限,-1 = 不限
     * @param used      今日已用
     * @param remaining 今日剩余,-1 = 不限
     */
    public record DailyUsage(String channel, boolean unlimited, int limit, long used, long remaining) {
    }
}
