package com.example.airag.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.airag.admin.dto.AdminOverview;
import com.example.airag.admin.dto.AdminUserView;
import com.example.airag.admin.dto.DailyLimitRequest;
import com.example.airag.admin.dto.UserCreateRequest;
import com.example.airag.admin.dto.UserUpdateRequest;
import com.example.airag.auth.LoginUser;
import com.example.airag.auth.PasswordEncoder;
import com.example.airag.common.BizException;
import com.example.airag.common.PageResult;
import com.example.airag.record.LogRepository;
import com.example.airag.record.RecordType;
import com.example.airag.user.User;
import com.example.airag.user.UserRepository;
import com.example.airag.user.UserRole;
import com.example.airag.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管理后台的用户管理。
 *
 * <p><b>本类承担的核心风险是"把管理员自己锁在门外"</b> —— 管理后台是唯一能改角色的地方,
 * 一旦最后一个可用管理员被降级 / 禁用 / 删除,就只能去数据库里手工改回来。所以所有会
 * 减少"可用管理员数量"的操作都过一遍 {@link #guardAgainstLockout}。</p>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final LogRepository logRepository;

    public AdminUserService(UserRepository userRepository,
                            UserService userService,
                            PasswordEncoder passwordEncoder,
                            LogRepository logRepository) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.logRepository = logRepository;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<AdminUserView> page(String keyword, String role, int page, int size) {
        UserRole filter = (role == null || role.isBlank() || "all".equalsIgnoreCase(role.trim()))
                ? null : UserRole.of(role);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        long total = userRepository.count(keyword, filter);
        List<User> users = userRepository.page(keyword, filter, (safePage - 1) * safeSize, safeSize);
        List<AdminUserView> items = new ArrayList<>(users.size());
        for (User user : users) {
            items.add(AdminUserView.of(user, todayUsage().get(user.getId())));
        }
        return PageResult.of(total, safePage, safeSize, items);
    }

    public AdminUserView view(long id) {
        return AdminUserView.of(requireUser(id), todayUsage().get(id));
    }

    /**
     * 所有人当天的成功提问次数,一次查出来。
     *
     * <p>用户列表每行都要显示今日用量,逐行查就是 N+1 次查询 —— 一次分组查询解决。</p>
     */
    private Map<Long, Map<String, Long>> todayUsage() {
        LocalDate today = LocalDate.now();
        return logRepository.successCountByUser(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    // ------------------------------------------------------------------ 写入

    public AdminUserView create(UserCreateRequest request) {
        int quotaTotal = request.getQuotaTotal() != null
                ? request.getQuotaTotal() : userService.defaultQuotaTotal();

        // 复用 UserService.create:账号非空、密码长度、账号查重这些校验都在那里
        User created = userService.create(request.getUsername(), request.getPassword(),
                request.getNickname(), quotaTotal);

        if (UserRole.of(request.getRole()).isAdmin()) {
            userRepository.updateRole(created.getId(), UserRole.ADMIN);
        }
        userRepository.updateDailyLimits(created.getId(), request.getDailyLimitTotal(),
                request.getDailyLimitFaq(), request.getDailyLimitAfterSale());
        return view(created.getId());
    }

    public AdminUserView update(long id, UserUpdateRequest request, LoginUser actor) {
        User user = requireUser(id);
        UserRole newRole = request.getRole() == null ? user.getRole() : UserRole.of(request.getRole());
        int newStatus = request.getStatus() == null ? user.getStatus() : (request.getStatus() == 1 ? 1 : 0);

        guardAgainstLockout(actor, id, user, newRole, newStatus);

        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            userRepository.updateNickname(id, request.getNickname().trim());
        }
        if (newRole != user.getRole()) {
            userRepository.updateRole(id, newRole);
        }
        if (newStatus != user.getStatus()) {
            userRepository.updateStatus(id, newStatus);
        }
        if (request.getQuotaTotal() != null) {
            userRepository.updateQuotaTotal(id, request.getQuotaTotal());
        }
        return view(id);
    }

    /** 设置每日上限:null = 跟随全局,-1 = 不限。整体覆盖。 */
    public AdminUserView updateDailyLimits(long id, DailyLimitRequest request) {
        requireUser(id);
        userRepository.updateDailyLimits(id, request.getTotal(), request.getFaq(), request.getAfterSale());
        // 打一条:前端缓存了旧版 admin.js 时,提交的报文里会没有 total,
        // 于是"整体覆盖"会把它悄悄清成 NULL —— 有这行日志一眼就能看出来。
        log.info("更新用户 {} 的每日上限:总量={}, FAQ={}, 售后={}(null=跟随全局)",
                id, request.getTotal(), request.getFaq(), request.getAfterSale());
        return view(id);
    }

    public AdminUserView resetQuotaUsed(long id) {
        requireUser(id);
        userRepository.resetQuotaUsed(id);
        return view(id);
    }

    public void resetPassword(long id, String rawPassword) {
        requireUser(id);
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        userRepository.updatePasswordHash(id, passwordEncoder.encode(rawPassword));
    }

    /**
     * 删除用户。
     *
     * <p>只删 {@code sys_user} 这一行,问答记录和使用日志<b>保留</b> —— 那是审计数据,
     * 人走了"他上个月用了多少次"还得查得到。真要清理得单独做。</p>
     */
    public void delete(long id, LoginUser actor) {
        User user = requireUser(id);
        if (actor.id() == id) {
            throw BizException.badRequest("不能删除自己的账号");
        }
        guardAgainstLockout(actor, id, user, UserRole.USER, 0);
        userRepository.deleteById(id);
    }

    // ------------------------------------------------------------------ 概览

    public AdminOverview overview() {
        LocalDate today = LocalDate.now();
        return new AdminOverview(
                userRepository.countAll(),
                userRepository.count(null, UserRole.ADMIN),
                userRepository.countDisabled(),
                statsBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                statsBetween(null, null));
    }

    private List<AdminOverview.ChannelStat> statsBetween(LocalDateTime from, LocalDateTime to) {
        List<AdminOverview.ChannelStat> list = new ArrayList<>();
        for (RecordType type : RecordType.values()) {
            long success = 0;
            long failed = 0;
            long rejected = 0;
            long tokens = 0;
            for (LogRepository.StatusCount row : logRepository.countByStatusBetween(type, null, from, to)) {
                switch (row.status()) {
                    case "SUCCESS" -> success += row.count();
                    case "FAILED" -> failed += row.count();
                    case "REJECTED" -> rejected += row.count();
                    default -> { }
                }
                tokens += row.tokens();
            }
            list.add(new AdminOverview.ChannelStat(type.pathKey(), success + failed + rejected,
                    success, failed, rejected, tokens));
        }
        return list;
    }

    // ------------------------------------------------------------------ 守卫

    /**
     * 拦住会让"可用管理员数量"降到 0 的操作。
     *
     * <p>两条规则合起来构成一把完整的锁:</p>
     * <ol>
     *   <li><b>不能动自己</b> —— 管理员不能把自己降级或禁用。这条是关键,因为能走到管理接口的人
     *       自己必然是一个启用的管理员;</li>
     *   <li><b>不能降级最后一个可用管理员</b> —— 看起来是另一条规则,其实由第 1 条蕴含:
     *       当目标是"最后一个可用管理员"时,那个目标只可能是操作者自己。留着它是防御性的
     *       冗余(万一以后允许非管理员身份的服务账号调用,或者拦截器与这里的判断不一致)。</li>
     * </ol>
     *
     * <p>注意判断的是"<b>改完之后</b>还是不是可用管理员",而不是只看请求里有没有降级 ——
     * 一个已经禁用的管理员账号,再禁用一次不该被拦。</p>
     */
    private void guardAgainstLockout(LoginUser actor, long targetId, User target, UserRole newRole, int newStatus) {
        boolean wasUsableAdmin = target.isAdmin() && target.getStatus() == 1;
        boolean stillUsableAdmin = newRole.isAdmin() && newStatus == 1;
        if (!wasUsableAdmin || stillUsableAdmin) {
            return;
        }
        if (actor.id() == targetId) {
            throw BizException.badRequest("不能取消自己的管理员身份或禁用自己的账号 —— 否则就没人能进管理后台了");
        }
        if (userRepository.countEnabledAdmins() <= 1) {
            throw BizException.badRequest("[" + target.getUsername()
                    + "] 是最后一个可用的管理员,不能降级或禁用 —— 否则就没人能进管理后台了");
        }
    }

    private User requireUser(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> BizException.badRequest("用户不存在: " + id));
    }
}
