package com.example.airag.record;

import com.example.airag.common.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 提问记录服务。
 *
 * <p>约定:记录落库失败绝不影响用户的对话结果,只记日志。</p>
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final RecordRepository recordRepository;

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /** 安全落库:任何异常都被吞掉并记录日志。 */
    public void saveQuietly(RecordType type, QuestionRecord record) {
        try {
            recordRepository.insert(type, record);
        } catch (Exception ex) {
            log.error("写入提问记录失败 type={} userId={}: {}", type, record.getUserId(), ex.getMessage(), ex);
        }
    }

    public PageResult<QuestionRecord> page(RecordType type, long userId, int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = recordRepository.count(type, userId, keyword);
        int offset = (safePage - 1) * safeSize;
        return PageResult.of(total, safePage, safeSize, recordRepository.page(type, userId, keyword, offset, safeSize));
    }
}
