package com.example.airag.user;

import com.example.airag.auth.PasswordEncoder;
import com.example.airag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时按需创建一个默认管理员账号
 *
 * <p>正式上线请把 {@code app.auth.default-admin.enabled} 设为 false,或者登录后立刻改密码。</p>
 *
 * <p>{@code @Order(10)}:必须晚于 {@link com.example.airag.config.SchemaMigrator}(order=0),
 * 否则 {@code role} 列还没补上,这里写角色就会报"列不存在"。</p>
 */
@Component
@Order(10)
public class DefaultAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminInitializer.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final AppProperties properties;

    public DefaultAdminInitializer(UserService userService, UserRepository userRepository, AppProperties properties) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.DefaultAdmin config = properties.auth().defaultAdmin();
        if (config.enabled() && !userRepository.existsByUsername(config.username())) {
            User created = userService.create(config.username(), config.password(), "系统管理员",
                    userService.defaultQuotaTotal());
            userRepository.updateRole(created.getId(), UserRole.ADMIN);
            log.warn("已创建默认管理员账号 [{}](id={}),初始密码 [{}] —— 请登录后尽快修改密码!",
                    created.getUsername(), created.getId(), config.password());
            log.warn("当前系统用户总数: {},如需关闭自动建号请设置 app.auth.default-admin.enabled=false",
                    userRepository.countAll());
        }
        if (config.enabled()) {
            recoverUnusablePassword(config);
        }
        ensureAdminExists(config.username());
    }

    /**
     * 兜底恢复:默认管理员账号存在、但 {@code password_hash} 不是本系统能识别的格式时,把它重置
     * 回配置里的初始密码。
     *
     * <p><b>为什么需要这一步:</b>最典型的故障就是有人直接往 {@code sys_user} 里插了一行、密码填了
     * 明文或者 MD5。这种账号永远登录不了,而且 —— 如果它恰好是唯一的管理员 ——
     * {@link #ensureAdminExists} 只看角色,会认为"有管理员",于是整个系统就没人能进后台了。
     * 更糟的是被锁在门外之后没有任何界面手段能自救,只能去数据库里手改。</p>
     *
     * <p>只在"哈希格式压根不可用"时才动手,所以<b>绝不会覆盖一个能正常登录的密码</b>;
     * 而且受 {@code app.auth.default-admin.enabled} 控制,正式环境关掉这个开关就不会有这条后门。</p>
     */
    private void recoverUnusablePassword(AppProperties.DefaultAdmin config) {
        User admin = userRepository.findByUsername(config.username()).orElse(null);
        if (admin == null || PasswordEncoder.isEncoded(admin.getPasswordHash())) {
            return;
        }
        userService.resetPassword(admin.getId(), config.password());
        if (admin.getStatus() != 1) {
            userRepository.updateStatus(admin.getId(), 1);
        }
        if (!admin.isAdmin()) {
            userRepository.updateRole(admin.getId(), UserRole.ADMIN);
        }
        log.error("默认管理员账号 [{}](id={}) 的 password_hash 不是本系统能识别的格式({}),"
                        + "这个账号本来永远登录不了。已把它重置为配置里的初始密码 [{}] 并确保它是启用的管理员。"
                        + " 请立刻用它登录,然后到 /admin.html 给其它账号重置密码。",
                admin.getUsername(), admin.getId(),
                PasswordEncoder.describeUnusable(admin.getPasswordHash()), config.password());
    }

    /**
     * 保证系统里至少有一个可用的管理员。
     *
     * <p>管理后台能改角色,所以存在一种"把自己锁在门外"的路径:最后一个管理员把自己降级、
     * 禁用或删掉,从此谁也进不去后台,只能去数据库里手改。这里只检查"<b>一个可用管理员都没有</b>"
     * 这一种情况,所以不会覆盖管理员主动做的降级 —— 只要还剩一个管理员,这里什么都不做。</p>
     */
    private void ensureAdminExists(String fallbackUsername) {
        if (userRepository.countEnabledAdmins() > 0) {
            return;
        }
        User target = userRepository.findByUsername(fallbackUsername).orElse(null);
        if (target == null) {
            log.error("系统里没有任何可用的管理员,默认管理员账号 [{}] 也不存在,管理后台将无人能进。"
                            + "请手工执行(把 your_account 换成你的账号):"
                            + " UPDATE sys_user SET role='ADMIN', status=1 WHERE username='your_account';",
                    fallbackUsername);
            return;
        }
        userRepository.promoteToAdmin(target.getId());
        if (target.getStatus() != 1) {
            userRepository.updateStatus(target.getId(), 1);
        }
        log.warn("系统里没有可用的管理员,已把 [{}] 提升为管理员并启用 —— 否则没人能进管理后台。",
                target.getUsername());
    }
}
