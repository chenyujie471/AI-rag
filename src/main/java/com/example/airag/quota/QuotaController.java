package com.example.airag.quota;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询当前登录用户的剩余次数(总额度 + 各链路今日用量)。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;
    private final DailyLimitService dailyLimitService;

    public QuotaController(QuotaService quotaService, DailyLimitService dailyLimitService) {
        this.quotaService = quotaService;
        this.dailyLimitService = dailyLimitService;
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> myQuota() {
        LoginUser user = UserContext.require();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", quotaService.snapshot(user.id()));
        body.put("daily", dailyLimitService.usageAll(user.id()));
        return ApiResponse.ok(body);
    }
}
