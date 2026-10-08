package com.example.airag.record;

import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提问记录查询(只查当前登录用户自己的数据)。
 *
 * <ul>
 *   <li>{@code GET /api/records/faq} —— AI 问题记录</li>
 *   <li>{@code GET /api/records/after-sale} —— 售后问题记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping("/{type}")
    public ApiResponse<PageResult<QuestionRecord>> page(@PathVariable String type,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String keyword) {
        RecordType recordType = RecordType.fromPathKey(type);
        LoginUser user = UserContext.require();
        return ApiResponse.ok(recordService.page(recordType, user.id(), page, size, keyword));
    }
}
