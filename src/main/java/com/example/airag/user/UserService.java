package com.example.airag.user;

import java.util.Optional;

import com.example.airag.auth.PasswordEncoder;
import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号服务:登录校验 + 用户查询 + 创建 + 自助改密码。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 新密码的最短长度。前端也校验一遍,这里才是真正说了算的。 */
    public static final int MIN_PASSWORD_LENGTH = 6;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    /** 账号不存在时也做一次哈希计算,避免通过响应耗时枚举账号。 */
    private final String dummyHash;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-placeholder");
    }

    /** 校验账号密码,成功返回用户并记录登录时间,失败抛 401。 */
    public User authenticate(String username, String rawPassword) {
        User user = verifyCredentials(username, rawPassword);
        userRepository.touchLastLogin(user.getId());
        return user;
    }

    /**
     * 自助修改密码:先核对原密码,再写入新密码。
     *
     * <p>和登录一样,这个动作也可能是"还没登录的人"发起的(登录页上的修改密码入口),
     * 所以身份完全靠<b>原密码</b>来证明,不依赖令牌。</p>
     */
    public void changePassword(String username, String rawOldPassword, String rawNewPassword) {
        if (rawNewPassword == null || rawNewPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("新密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (rawNewPassword.equals(rawOldPassword)) {
            throw BizException.badRequest("新密码不能和原密码相同");
        }
        // 原密码不对会在这里抛 401,后面的写操作不会执行
        User user = verifyCredentials(username, rawOldPassword);
        userRepository.updatePasswordHash(user.getId(), passwordEncoder.encode(rawNewPassword));
        log.info("账号 [{}](id={}) 修改了自己的密码", user.getUsername(), user.getId());
    }

    /**
     * 直接重设某个账号的密码,<b>不需要原密码</b>。
     *
     * <p>给两个调用方用:管理员在后台点「重置密码」,以及启动时的兜底恢复
     * (见 {@link DefaultAdminInitializer})。所以这里只做长度校验,调用方负责确认权限。</p>
     */
    public void resetPassword(long userId, String rawNewPassword) {
        if (rawNewPassword == null || rawNewPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("新密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        userRepository.updatePasswordHash(userId, passwordEncoder.encode(rawNewPassword));
    }

    /**
     * 把"账号 + 密码"校验成用户,成功才返回。失败一律抛 401,不透露是哪一项不对。
     *
     * <p>特意不记录登录时间 —— 改密码也要走这里,但改密码不是登录。</p>
     */
    private User verifyCredentials(String username, String rawPassword) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw BizException.badRequest("账号和密码不能为空");
        }
        Optional<User> found = userRepository.findByUsername(normalized);
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw BizException.unauthorized("账号或密码错误");
        }
        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            reportUnusableHash(user);
            throw BizException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() != 1) {
            throw BizException.forbidden("账号已被禁用,请联系管理员");
        }
        return user;
    }

    /**
     * 密码对不上时,顺手判断一下是不是"存储的哈希格式压根不对"。
     *
     * <p>这是直接往数据库里插账号时最容易踩的坑:把明文、MD5 或者别处复制来的哈希填进
     * {@code password_hash},于是 {@code matches} 永远返回 false,用户只看到一句
     * "账号或密码错误",完全无从下手。所以这里在服务端日志里把原因和修法写清楚
     * (对客户端仍然只说"账号或密码错误",不泄露账号是否存在)。</p>
     */
    private void reportUnusableHash(User user) {
        String stored = user.getPasswordHash();
        if (PasswordEncoder.isEncoded(stored)) {
            return;   // 格式没问题,就是密码打错了 —— 正常情况,不用刷日志
        }
        log.warn("账号 [{}](id={}) 的 password_hash 不是本系统能识别的格式,所以永远验不过密码。{}\n"
                        + "  正确格式形如 pbkdf2$120000$<盐>$<哈希>,必须由 PasswordEncoder 生成。\n"
                        + "  修复:java -cp target/classes com.example.airag.auth.PasswordEncoder <新密码> {}\n"
                        + "  然后执行它打印出来的 UPDATE 语句;或直接去 /admin.html 用管理员重置密码。",
                user.getUsername(), user.getId(), PasswordEncoder.describeUnusable(stored), user.getUsername());
    }

    public User requireById(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> BizException.tokenInvalid("用户不存在或已被删除"));
    }

    public User create(String username, String rawPassword, String nickname, int quotaTotal) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty()) {
            throw BizException.badRequest("账号不能为空");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (userRepository.existsByUsername(normalized)) {
            throw BizException.badRequest("账号已存在: " + normalized);
        }
        User user = new User();
        user.setUsername(normalized);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname == null || nickname.isBlank() ? normalized : nickname.trim());
        user.setStatus(1);
        user.setQuotaTotal(quotaTotal);
        user.setQuotaUsed(0);
        userRepository.insert(user);
        return user;
    }

    /** 新用户默认额度,取自 app.quota.default-total(-1 表示不限)。 */
    public int defaultQuotaTotal() {
        return properties.quota().defaultTotal();
    }
}
