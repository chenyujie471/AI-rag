package com.example.airag.user;

import com.example.airag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 让"终身总额度"退场:启动时把老账号残留的 {@code quota_total} 抹成 {@code -1}(不限)。
 *
 * <p><b>为什么需要这个。</b> {@code app.quota.default-total} 只决定<b>新</b>账号建出来是多少,
 * 对已经在库里的行没有任何影响。老库里的账号带着 {@code quota_total = 100} 这样的旧值,
 * 照样会在 {@code QuotaService.tryConsume} 那里被拦下来 —— 表现就是"明明只在管理后台调了每日上限,
 * 怎么还是说次数用完了"。这里一次性抹平,消掉这个隐蔽的第二个限制。</p>
 *
 * <p><b>为什么用 {@code default-total} 当开关。</b> 只有 <b>负数</b>(也就是"默认不限")才说明
 * 运维已经声明不用终身额度了,这时候清理老数据才是符合意图的。如果显式配了一个正数,
 * 说明确实要用终身额度,那就一个字都别动 —— 这条规则也让几个显式设了
 * {@code app.quota.default-total=3/100} 的集成测试不受影响。</p>
 *
 * <p>{@code quota_used} 不动:它只是"累计扣过多少次"的记账,当计数看仍然有意义,
 * 清掉反而丢信息。</p>
 */
@Component
@Order(5)
public class LifetimeQuotaCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LifetimeQuotaCleanup.class);

    private final UserRepository userRepository;
    private final AppProperties properties;

    public LifetimeQuotaCleanup(UserRepository userRepository, AppProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int defaultTotal = properties.quota().defaultTotal();
        if (defaultTotal >= 0) {
            log.info("终身额度仍在使用中(app.quota.default-total={}),跳过老数据清理", defaultTotal);
            return;
        }

        int affected = userRepository.resetAllQuotaTotalsToUnlimited();
        if (affected == 0) {
            // 每次启动都会跑,正常情况下第二遍就是 0 行,不值得打日志
            log.debug("没有需要清理的终身额度");
            return;
        }
        log.warn("终身额度已退役:{} 个账号残留的 quota_total 已改成 -1(不限)。"
                        + "现在唯一控量的是每账号每日总量 —— 全局默认 app.quota.daily-limit.per-user={},"
                        + "可以在管理后台按账号单独覆盖(sys_user.daily_limit_total)。"
                        + "想恢复终身额度,把 app.quota.default-total 配成正数即可。",
                affected, properties.quota().dailyLimit().perUser());
    }
}
