package com.example.airag.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.airag.admin.dto.AdminOverview;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.PageResult;
import com.example.airag.record.LogEntry;
import com.example.airag.record.LogQuery;
import com.example.airag.record.LogRepository;
import com.example.airag.record.RecordType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台 · 全站使用记录与概览。
 *
 * <p>和面向普通用户的 {@link com.example.airag.record.LogController} 的区别只有一个:
 * 那边把 {@code userId} 钉死成当前登录用户,这边可以传 {@code userId} 或者不传(看全部)。
 * 权限由 {@code AdminInterceptor} 保证。</p>
 *
 * <ul>
 *   <li>{@code GET /api/admin/logs}     全站调用记录</li>
 *   <li>{@code GET /api/admin/logs/stats} 按链路/状态的汇总</li>
 *   <li>{@code GET /api/admin/overview} 概览(用户数 + 今日/累计统计)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminLogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LogRepository logRepository;
    private final AdminUserService adminUserService;

    public AdminLogController(LogRepository logRepository, AdminUserService adminUserService) {
        this.logRepository = logRepository;
        this.adminUserService = adminUserService;
    }

    @GetMapping("/logs")
    public ApiResponse<PageResult<LogEntry>> logs(@RequestParam(required = false) Long userId,
                                                  @RequestParam(required = false) String username,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String start,
                                                  @RequestParam(required = false) String end,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        LogQuery query = LogQuery.of(type, status, keyword, start, end);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = logRepository.count(userId, username, query);
        List<LogEntry> items = logRepository.page(userId, username, query, (safePage - 1) * safeSize, safeSize);
        return ApiResponse.ok(PageResult.of(total, safePage, safeSize, items));
    }

    /**
     * 按链路、状态汇总;{@code userId} 不传就是全站。
     *
     * <p>返回形如:
     * <pre>{ "faq": {"total":3,"success":2,"failed":0,"rejected":1,"tokens":120}, ... }</pre>
     */
    @GetMapping("/logs/stats")
    public ApiResponse<Map<String, ChannelStats>> stats(@RequestParam(required = false) Long userId) {
        Map<String, ChannelStats> body = new LinkedHashMap<>();
        for (RecordType type : RecordType.values()) {
            long success = 0;
            long failed = 0;
            long rejected = 0;
            long tokens = 0;
            for (LogRepository.StatusCount row : logRepository.countByStatus(type, userId)) {
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

    @GetMapping("/overview")
    public ApiResponse<AdminOverview> overview() {
        return ApiResponse.ok(adminUserService.overview());
    }

    public record ChannelStats(long total, long success, long failed, long rejected, long tokens) {
    }
}
