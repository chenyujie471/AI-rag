package com.example.airag.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用日志(只查当前登录用户自己的数据)。
 *
 * <ul>
 *   <li>{@code GET /api/logs} —— 两条链路合并的调用记录,支持 type / status / keyword / start / end 筛选</li>
 *   <li>{@code GET /api/logs/stats} —— 按链路、状态汇总的调用次数与 token</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LogRepository logRepository;

    public LogController(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @GetMapping
    public ApiResponse<PageResult<LogEntry>> page(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String start,
                                                  @RequestParam(required = false) String end,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        LoginUser user = UserContext.require();
        LogQuery query = LogQuery.of(type, status, keyword, start, end);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = logRepository.count(user.id(), query);
        List<LogEntry> items = logRepository.page(user.id(), query, (safePage - 1) * safeSize, safeSize);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, items));
    }

    /**
     * 返回形如:
     * <pre>{ "faq": {"total":3,"success":2,"failed":0,"rejected":1,"tokens":120}, "after-sale": {...} }</pre>
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, ChannelStats>> stats() {
        LoginUser user = UserContext.require();
        Map<String, ChannelStats> body = new LinkedHashMap<>();
        for (RecordType type : RecordType.values()) {
            long success = 0;
            long failed = 0;
            long rejected = 0;
            long tokens = 0;
            for (LogRepository.StatusCount row : logRepository.countByStatus(type, user.id())) {
                switch (row.status()) {
                    case "SUCCESS" -> success += row.count();
                    case "FAILED" -> failed += row.count();
                    case "REJECTED" -> rejected += row.count();
                    default -> { }
                }
                tokens += row.tokens();
            }
            body.put(type.pathKey(), new ChannelStats(success + failed + rejected, success, failed, rejected, tokens));
        }
        return ApiResponse.ok(body);
    }

    public record ChannelStats(long total, long success, long failed, long rejected, long tokens) {
    }
}
