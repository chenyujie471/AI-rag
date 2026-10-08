package com.example.airag.user;

import java.util.List;

import com.example.airag.auth.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动自检:找出 {@code password_hash} 格式不对、因此<b>永远登录不了</b>的账号。
 *
 * <p><b>为什么值得单独做一次自检:</b>项目允许直接往 {@code sys_user} 里插账号(运维常用),
 * 但密码必须是 {@link PasswordEncoder} 生成的 {@code pbkdf2$...} 哈希。填了明文、MD5、
 * 或者从别处复制的哈希时,登录只会返回一句"账号或密码错误",现象和"密码打错了"完全一样 ——
 * 不去翻日志根本看不出区别,每次都要重新排查一遍。</p>
 *
 * <p>这里在启动时一次性把所有这种账号列出来,把"踩坑后排查"变成"开机就知道"。</p>
 *
 * <p>{@code @Order(20)}:晚于 {@link DefaultAdminInitializer}(order=10),这样如果默认管理员
 * 刚被它兜底修复过,这里报出来的就是修复之后的真实状态,不会误报。</p>
 */
@Component
@Order(20)
public class PasswordHashAuditor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordHashAuditor.class);

    private final UserRepository userRepository;

    public PasswordHashAuditor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<User> all = userRepository.findAll();
        if (all.isEmpty()) {
            return;
        }
        List<User> unusable = all.stream()
                .filter(user -> !PasswordEncoder.isEncoded(user.getPasswordHash()))
                .toList();
        if (unusable.isEmpty()) {
            log.info("账号自检:{} 个账号的 password_hash 格式均正常", all.size());
            return;
        }
        // 只报账号和长度,不回显值 —— 这里的内容往往就是明文密码,不该进日志文件
        log.error("账号自检:发现 {} 个账号的 password_hash 不是本系统能识别的格式,**这些账号永远登录不了**"
                + "(共 {} 个账号):", unusable.size(), all.size());
        for (User user : unusable) {
            log.error("  - [{}](id={}, role={}, status={}) {}",
                    user.getUsername(), user.getId(), user.getRole(),
                    user.getStatus() == 1 ? "启用" : "已禁用",
                    PasswordEncoder.describeUnusable(user.getPasswordHash()));
        }
        log.error("修法(任选其一):"
                + "\n  1) 登录管理员 → /admin.html → 找到这些账号 → 点「重置密码」"
                + "\n  2) java -cp target/classes com.example.airag.auth.PasswordEncoder <新密码> <账号>"
                + " 然后执行它打印出来的 UPDATE 语句");
    }
}
