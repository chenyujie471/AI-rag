package com.example.airag.gateway;

import java.io.IOException;

import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.BizException;
import com.example.airag.common.ErrorCode;
import com.example.airag.common.JsonResponseWriter;
import com.example.airag.config.AppProperties;
import com.example.airag.quota.DailyLimitService;
import com.example.airag.quota.QuotaInfo;
import com.example.airag.quota.QuotaService;
import com.example.airag.record.QuestionRecord;
import com.example.airag.record.RecordService;
import com.example.airag.record.RecordStatus;
import com.example.airag.record.RecordType;
import com.example.airag.usage.ClientInfoResolver;
import com.example.airag.usage.UsageLog;
import com.example.airag.usage.UsageLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 对话网关核心流程(方案 A 的"网关层拦截"就落在这里):
 *
 * <pre>
 * 浏览器 -> /api/chat/{faq|after-sale}
 *        -> 1. 校验 JWT(由 AuthInterceptor 完成)
 *        -> 2. 校验频率限制(由 RateLimitInterceptor 完成)
 *        -> 3. 查额度并原子扣减;不足直接返回,绝不转发给 FastGPT
 *        -> 4. 转发到对应 FastGPT 应用(流式原样透传)
 *        -> 5. 落库:内容进 ai_question_record / after_sale_question_record,
 *              账号使用账进 usage_log(不含问答内容)
 * </pre>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_QUESTION_LENGTH = 2000;
    private static final int MAX_CHAT_ID_LENGTH = 200;
    private static final int MAX_ERROR_TEXT_LENGTH = 400;

    private final AppProperties properties;
    private final QuotaService quotaService;
    private final RecordService recordService;
    private final FastGptClient fastGptClient;
    private final JsonResponseWriter responseWriter;
    private final DailyLimitService dailyLimitService;
    private final UsageLogService usageLogService;
    private final ClientInfoResolver clientInfoResolver;

    public ChatService(AppProperties properties, QuotaService quotaService, RecordService recordService,
                       FastGptClient fastGptClient, JsonResponseWriter responseWriter,
                       DailyLimitService dailyLimitService, UsageLogService usageLogService,
                       ClientInfoResolver clientInfoResolver) {
        this.properties = properties;
        this.quotaService = quotaService;
        this.dailyLimitService = dailyLimitService;
        this.recordService = recordService;
        this.fastGptClient = fastGptClient;
        this.responseWriter = responseWriter;
        this.usageLogService = usageLogService;
        this.clientInfoResolver = clientInfoResolver;
    }

    public void chat(RecordType type, ChatRequest request, HttpServletResponse response) throws IOException {
        LoginUser user = UserContext.require();
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw BizException.badRequest("问题不能为空");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw BizException.badRequest("问题过长,最多 " + MAX_QUESTION_LENGTH + " 个字符");
        }

        AppProperties.Gateway gateway = properties.gateway();
        if (!gateway.enabled()) {
            throw BizException.badRequest("对话网关未启用(app.gateway.enabled=false)");
        }
        AppProperties.FastGptApp app = type == RecordType.AI_QUESTION ? gateway.faq() : gateway.afterSale();

        // 会话 ID 强制按用户做命名空间隔离,防止拿到别人的上下文
        String chatId = resolveChatId(request.getChatId(), user, type);
        // TOKEN 扣减模式必须关闭流式,否则拿不到准确 usage
        boolean stream = request.isStream() && quotaService.deductMode() == AppProperties.DeductMode.REQUEST;

        // ---------- 0. 每日渠道上限(app.quota.daily-limit.*):满了直接拦,不扣总额度 ----------
        DailyLimitService.Permit permit;
        try {
            permit = dailyLimitService.acquire(type, user.id());
        } catch (BizException limited) {
            saveRecord(type, user, question, chatId, app, null, ChatUsage.empty(), 0,
                    RecordStatus.REJECTED, limited.getMessage(), 0L);
            throw limited;
        }
        // 名额要一直占到记录落库之后再释放,否则并发请求会看到"少算一次"
        try (permit) {
            forward(type, request, user, question, chatId, app, stream, response);
        }
    }

    private void forward(RecordType type, ChatRequest request, LoginUser user, String question, String chatId,
                         AppProperties.FastGptApp app, boolean stream, HttpServletResponse response)
            throws IOException {
        // ---------- 1. 前置拦截:额度不足 / 账号禁用,直接不转发 ----------
        int reserved = 0;
        if (quotaService.enabled()) {
            if (quotaService.deductMode() == AppProperties.DeductMode.REQUEST) {
                if (!quotaService.tryConsume(user.id(), 1)) {
                    BizException failure = quotaService.explainConsumeFailure(user.id());
                    saveRecord(type, user, question, chatId, app, null, ChatUsage.empty(), 0,
                            RecordStatus.REJECTED, failure.getMessage(), 0L);
                    throw failure;
                }
                reserved = 1;
            } else {
                QuotaInfo before = quotaService.snapshot(user.id());
                if (!before.unlimited() && before.remaining() <= 0) {
                    BizException failure = BizException.quotaExhausted("调用次数已用完(" + before.used() + "/"
                            + before.total() + "),请联系管理员增加额度");
                    saveRecord(type, user, question, chatId, app, null, ChatUsage.empty(), 0,
                            RecordStatus.REJECTED, failure.getMessage(), 0L);
                    throw failure;
                }
            }
        }

        // ---------- 2. 转发到 FastGPT ----------
        long startedAt = System.currentTimeMillis();
        FastGptResult result;
        try {
            result = fastGptClient.call(app, chatId, question, stream, response);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startedAt;
            int refunded = refundIfNeeded(user.id(), reserved);
            saveRecord(type, user, question, chatId, app, null, ChatUsage.empty(), refunded,
                    RecordStatus.FAILED, "网关调用异常: " + ex.getMessage(), duration);
            throw new BizException(ErrorCode.UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY.value(),
                    "调用知识库服务失败: " + ex.getMessage(), ex);
        }
        long duration = System.currentTimeMillis() - startedAt;

        // ---------- 3. 成功:结算 + 落库 ----------
        if (result.success()) {
            int cost = reserved;
            if (quotaService.enabled() && quotaService.deductMode() == AppProperties.DeductMode.TOKEN) {
                cost = Math.max(1, (int) Math.ceil(
                        result.usage().totalTokens() / (double) quotaService.tokensPerUnit()));
                if (!quotaService.tryConsume(user.id(), cost)) {
                    // 上游已经返回内容,这里只能记账告警,不能反过来拒绝用户
                    log.warn("用户 {} 本次应扣 {} 次但余额不足,已超额放行(上游已返回结果)", user.id(), cost);
                }
            }
            saveRecord(type, user, question, chatId, app, result.answer(), result.usage(), cost,
                    RecordStatus.SUCCESS, null, duration);
            if (!result.clientCommitted()) {
                QuotaInfo after = quotaService.snapshot(user.id());
                response.setHeader("X-Quota-Remaining", after.unlimited() ? "-1" : String.valueOf(after.remaining()));
                responseWriter.write(response, HttpStatus.OK,
                        ApiResponse.ok(new ChatResponse(result.answer(), result.usage(), after)));
            }
            return;
        }

        // ---------- 4. 失败:退还额度 + 落库 ----------
        int cost = refundIfNeeded(user.id(), reserved);
        saveRecord(type, user, question, chatId, app, result.answer(), result.usage(), cost,
                RecordStatus.FAILED, result.errorMessage(), duration);
        if (!result.clientCommitted()) {
            responseWriter.write(response, HttpStatus.BAD_GATEWAY,
                    ApiResponse.fail(ErrorCode.UPSTREAM_ERROR, result.errorMessage()));
        }
    }

    /** 上游失败时退还已扣次数,返回实际保留的扣减数(退还后为 0)。 */
    private int refundIfNeeded(long userId, int reserved) {
        if (reserved > 0 && quotaService.refundOnFailure()) {
            quotaService.refund(userId, reserved);
            return 0;
        }
        return reserved;
    }

    /**
     * 会话 ID:统一前缀 {@code u<用户ID>-<链路>-},只保留安全字符。
     *
     * <p>这样即使有人伪造 chatId,也只能命中自己的会话,不会读到别人的上下文。</p>
     */
    private String resolveChatId(String raw, LoginUser user, RecordType type) {
        String sanitized = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty()) {
            sanitized = FastGptClient.randomChatId();
        }
        String candidate = "u" + user.id() + "-" + type.pathKey() + "-" + sanitized;
        return candidate.length() <= MAX_CHAT_ID_LENGTH ? candidate : candidate.substring(0, MAX_CHAT_ID_LENGTH);
    }

    private void saveRecord(RecordType type, LoginUser user, String question, String chatId,
                            AppProperties.FastGptApp app, String answer, ChatUsage usage, int costQuota,
                            RecordStatus status, String errorMsg, long durationMs) {
        QuestionRecord record = new QuestionRecord();
        record.setUserId(user.id());
        record.setUsername(user.username());
        record.setSessionId(chatId);
        record.setQuestion(question);
        record.setAnswer(answer);
        record.setAppId(app.appId().isBlank() ? null : app.appId());
        record.setPromptTokens(usage.promptTokens());
        record.setCompletionTokens(usage.completionTokens());
        record.setTotalTokens(usage.totalTokens());
        record.setCostQuota(costQuota);
        record.setStatus(status.name());
        record.setErrorMsg(abbreviate(errorMsg));
        record.setDurationMs(durationMs);
        recordService.saveQuietly(type, record);
        saveUsageLog(type, user, chatId, app, usage, costQuota, status, errorMsg, durationMs);
    }

    /**
     * 账号使用账(usage_log):只记用量与来源,不记问题与回答 —— 内容由那两张记录表负责。
     *
     * <p>与内容记录同样走"记账失败不影响对话"的策略。三种结果都会留下一行:
     * 成功记实际扣减次数,被拦截 / 上游失败记 {@code cost_quota=0},
     * 因此这张表可以直接拿来统计"某人今天发起过多少次、被挡了几次"。</p>
     *
     * <p>FastGPT 内部的节点流转不在采集范围内:调用上游时传 {@code detail=false},
     * 且只解析 {@code choices[].delta.content} 与 {@code usage}。</p>
     */
    private void saveUsageLog(RecordType type, LoginUser user, String chatId, AppProperties.FastGptApp app,
                              ChatUsage usage, int costQuota, RecordStatus status, String errorMsg,
                              long durationMs) {
        ClientInfoResolver.ClientInfo client = clientInfoResolver.resolve();
        UsageLog entry = new UsageLog();
        entry.setUserId(user.id());
        entry.setUsername(user.username());
        entry.setChannel(type.pathKey());
        entry.setSessionId(chatId);
        entry.setAppId(app.appId().isBlank() ? null : app.appId());
        entry.setPromptTokens(usage.promptTokens());
        entry.setCompletionTokens(usage.completionTokens());
        entry.setTotalTokens(usage.totalTokens());
        entry.setCostQuota(costQuota);
        entry.setStatus(status.name());
        entry.setErrorMsg(abbreviate(errorMsg));
        entry.setDurationMs(durationMs);
        entry.setClientIp(client.ip());
        entry.setUserAgent(client.userAgent());
        usageLogService.saveQuietly(entry);
    }

    /** error_msg 列是 VARCHAR(512),这里先截断避免插入失败。 */
    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String single = text.replaceAll("\\s+", " ").trim();
        if (single.isEmpty()) {
            return null;
        }
        return single.length() <= MAX_ERROR_TEXT_LENGTH
                ? single
                : single.substring(0, MAX_ERROR_TEXT_LENGTH) + "...";
    }
}
