package com.example.airag.admin;

import com.example.airag.admin.dto.AdminUserView;
import com.example.airag.admin.dto.DailyLimitRequest;
import com.example.airag.admin.dto.PasswordResetRequest;
import com.example.airag.admin.dto.UserCreateRequest;
import com.example.airag.admin.dto.UserUpdateRequest;
import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台 · 用户管理。
 *
 * <p>整个 {@code /api/admin/**} 由 {@code AdminInterceptor} 统一把守(非管理员一律 403),
 * 所以这里不再逐个方法做权限判断。</p>
 *
 * <ul>
 *   <li>{@code GET    /api/admin/users}                       列表(支持账号模糊搜 + 角色筛)</li>
 *   <li>{@code GET    /api/admin/users/{id}}                  单个用户</li>
 *   <li>{@code POST   /api/admin/users}                       新建</li>
 *   <li>{@code PUT    /api/admin/users/{id}}                  改昵称/角色/状态/总额度</li>
 *   <li>{@code PUT    /api/admin/users/{id}/daily-limit}      改每日上限(null=跟随全局)</li>
 *   <li>{@code PUT    /api/admin/users/{id}/password}         重置密码</li>
 *   <li>{@code POST   /api/admin/users/{id}/quota/reset}      把已用次数清零</li>
 *   <li>{@code DELETE /api/admin/users/{id}}                  删除账号(记录保留)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminUserView>> page(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String role,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.page(keyword, role, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserView> view(@PathVariable long id) {
        return ApiResponse.ok(adminUserService.view(id));
    }

    @PostMapping
    public ApiResponse<AdminUserView> create(@RequestBody UserCreateRequest request) {
        return ApiResponse.ok(adminUserService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUserView> update(@PathVariable long id, @RequestBody UserUpdateRequest request) {
        LoginUser actor = UserContext.require();
        return ApiResponse.ok(adminUserService.update(id, request, actor));
    }

    @PutMapping("/{id}/daily-limit")
    public ApiResponse<AdminUserView> updateDailyLimits(@PathVariable long id,
                                                        @RequestBody DailyLimitRequest request) {
        return ApiResponse.ok(adminUserService.updateDailyLimits(id, request));
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable long id, @RequestBody PasswordResetRequest request) {
        adminUserService.resetPassword(id, request.getPassword());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/quota/reset")
    public ApiResponse<AdminUserView> resetQuotaUsed(@PathVariable long id) {
        return ApiResponse.ok(adminUserService.resetQuotaUsed(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        LoginUser actor = UserContext.require();
        adminUserService.delete(id, actor);
        return ApiResponse.ok();
    }
}
