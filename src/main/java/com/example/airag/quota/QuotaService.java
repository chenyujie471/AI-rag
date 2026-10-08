package com.example.airag.quota;

import com.example.airag.common.BizException;
import com.example.airag.config.AppProperties;
import com.example.airag.user.User;
import com.example.airag.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「每人独立次数额度」的核心实现。
 *
 * <p>与 FastGPT 原生分享链接额度(链接级、无法区分用户)不同,这里按 {@code sys_user.id} 计数,
 * 扣减使用带条件的原子 UPDATE,天然并发安全,不依赖锁。</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final UserRepository userRepository;
    private final AppProperties properties;

    public QuotaService(UserRepository userRepository, AppProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.quota().enabled();
    }

    public AppProperties.DeductMode deductMode() {
        return properties.quota().deductMode();
    }

    public int tokensPerUnit() {
        return properties.quota().tokensPerUnit();
    }

    public boolean refundOnFailure() {
        return properties.quota().refundOnFailure();
    }

    /** 读取当前余额。 */
    public QuotaInfo snapshot(long userId) {
        return toInfo(requireUser(userId));
    }

    /**
     * 预扣次数。
     *
     * @return true=扣减成功;false=余额不足或账号已被禁用
     */
    public boolean tryConsume(long userId, int amount) {
        if (!enabled() || amount <= 0) {
            return true;
        }
        int affected = userRepository.tryConsumeQuota(userId, amount);
        return affected > 0;
    }

    /** 退还次数(上游失败补偿)。 */
    public void refund(long userId, int amount) {
        if (!enabled() || amount <= 0) {
            return;
        }
        int affected = userRepository.refundQuota(userId, amount);
        log.debug("退还用户 {} 的 {} 次额度,影响行数={}", userId, amount, affected);
    }

    /**
     * 扣减失败时给出准确原因(额度耗尽 / 账号禁用),避免把两种情况的提示搞混。
     */
    public BizException explainConsumeFailure(long userId) {
        QuotaInfo info = snapshot(userId);
        if (!info.unlimited() && info.remaining() <= 0) {
            return BizException.quotaExhausted("调用次数已用完(" + info.used() + "/" + info.total() + "),请联系管理员增加额度");
        }
        return BizException.forbidden("账号已被禁用,请联系管理员");
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BizException.tokenInvalid("用户不存在或已被删除"));
    }

    /** 实体 -> 快照。 */
    public static QuotaInfo toInfo(User user) {
        boolean unlimited = user.getQuotaTotal() < 0;
        int total = user.getQuotaTotal();
        int used = user.getQuotaUsed();
        int remaining = unlimited ? Integer.MAX_VALUE : Math.max(total - used, 0);
        return new QuotaInfo(unlimited, total, used, remaining);
    }
}
