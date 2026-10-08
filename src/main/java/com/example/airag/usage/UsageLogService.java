package com.example.airag.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号使用记录服务。
 *
 * <p>和 {@code RecordService} 一样的约定:记账落库失败绝不影响用户的对话结果,只记日志。
 * 记账是"顺带做的事",不能因为它把用户的提问搞失败。</p>
 */
@Service
public class UsageLogService {

    private static final Logger log = LoggerFactory.getLogger(UsageLogService.class);

    private final UsageLogRepository usageLogRepository;

    public UsageLogService(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    /** 安全落库:任何异常都被吞掉并记录日志。 */
    public void saveQuietly(UsageLog entry) {
        try {
            usageLogRepository.insert(entry);
        } catch (Exception ex) {
            log.error("写入账号使用记录失败 userId={} channel={}: {}",
                    entry.getUserId(), entry.getChannel(), ex.getMessage(), ex);
        }
    }
}
