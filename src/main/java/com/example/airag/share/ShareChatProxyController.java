package com.example.airag.share;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.example.airag.auth.AuthCookie;
import com.example.airag.auth.LoginUser;
import com.example.airag.auth.UserContext;
import com.example.airag.common.ApiResponse;
import com.example.airag.common.BizException;
import com.example.airag.common.JsonResponseWriter;
import com.example.airag.config.AppProperties;
import com.example.airag.gateway.ChatUsage;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 分享页反代的"计费拦截点"。
 *
 * <p><b>它解决什么问题:</b> FastGPT 分享页被 Nginx 反向代理到我们自己的源上之后,
 * 用户在分享页里发消息,请求会打到我们域名下的 {@code /api/v1/chat/completions}。
 * Nginx 把这两个路径路由到本控制器,于是配额与记账就发生在"请求的必经之路上"——
 * 这是能让"跳 FastGPT 原版页面"与"按账号限流 + 记录"同时成立的唯一办法。</p>
 *
 * <p><b>它是透传,不是改写:</b> 除了按需给 {@code chatId} 加账号前缀之外,请求体原样
 * 发给 FastGPT,响应也原样回给浏览器。这样做的好处是我们不需要理解 FastGPT 的协议细节,
 * 它升级协议我们也不用跟着改。我们只在旁边"读一眼"回答增量与 usage 用于记账。</p>
 *
 * <p><b>失败时绝不静默放行:</b> 配置没开、额度不够、当日次数用完,都会明确返回错误。
 * 静默放行等于配额失效,那是最糟的失败方式。</p>
 *
 * <p>路径说明:这里映射的两个路径是 FastGPT 常见的对话端点。若用浏览器 F12 发现分享页
 * 实际打的是别的路径,把那个路径加到下面的 {@code @PostMapping} 上,同时在 Nginx 里
 * 也加一份同样的路由即可。</p>
 */
@RestController
public class ShareChatProxyController {

    private static final Logger log = LoggerFactory.getLogger(ShareChatProxyController.class);

    private static final int ERROR_TEXT_LIMIT = 400;
    private static final int MAX_CHAT_ID_LENGTH = 200;

    private final AppProperties properties;
    private final QuotaService quotaService;
    private final DailyLimitService dailyLimitService;
    private final RecordService recordService;
    private final UsageLogService usageLogService;
    private final ClientInfoResolver clientInfoResolver;
    private final JsonResponseWriter responseWriter;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 配置里的 channel 解析出来的链路,构造时就校验,配错直接启动失败而不是悄悄写错表。 */
    private final RecordType channelType;

    public ShareChatProxyController(AppProperties properties, QuotaService quotaService,
                                    DailyLimitService dailyLimitService, RecordService recordService,
                                    UsageLogService usageLogService, ClientInfoResolver clientInfoResolver,
                                    JsonResponseWriter responseWriter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.quotaService = quotaService;
        this.dailyLimitService = dailyLimitService;
        this.recordService = recordService;
        this.usageLogService = usageLogService;
        this.clientInfoResolver = clientInfoResolver;
        this.responseWriter = responseWriter;
        this.objectMapper = objectMapper;
        this.channelType = resolveChannelType(properties);

        AppProperties.ShareProxy config = properties.shareProxy();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // 每日上限的计数来源是内容表里当天 SUCCESS 的行数(见 DailyLimitService)。
        // 关掉内容落库 = 每日计数永远不增长 = 每日上限静默失效。这里提前吼一声。
        // "每账号每日总量"也走同一套计数,所以一样会失效,得一起检查。
        if (config.usable() && !config.storeContent()) {
            int perUserLimit = properties.quota().dailyLimit().perUser();
            int channelLimit = dailyLimitFor(properties, channelType);
            if (perUserLimit >= 0 || channelLimit >= 0) {
                log.warn("app.share-proxy.store-content=false,但配置了每日上限"
                                + "(每账号每日总量 daily-limit.per-user={}, {}={};-1 表示不限):"
                                + "由于每日计数取自内容表,这些上限将永远不触发。"
                                + "若确实要限流,请把 store-content 设为 true。",
                        perUserLimit, channelType.pathKey(), channelLimit);
            }
        }
    }

    private static RecordType resolveChannelType(AppProperties properties) {
        AppProperties.ShareProxy config = properties.shareProxy();
        if (!config.usable()) {
            return RecordType.AI_QUESTION;
        }
        try {
            return RecordType.fromPathKey(config.channel());
        } catch (BizException ex) {
            throw new IllegalStateException("app.share-proxy.channel 配置非法: '" + config.channel()
                    + "',可选值 faq / after-sale", ex);
        }
    }

    private static int dailyLimitFor(AppProperties properties, RecordType type) {
        AppProperties.DailyLimit limit = properties.quota().dailyLimit();
        return type == RecordType.AI_QUESTION ? limit.faq() : limit.afterSale();
    }

    @PostMapping({"/api/v1/chat/completions", "/api/core/chat/completions"})
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AppProperties.ShareProxy config = properties.shareProxy();
        if (!config.usable()) {
            log.warn("收到反代对话请求,但 app.share-proxy 未启用:{} {}", request.getMethod(), request.getRequestURI());
            responseWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE, ApiResponse.fail(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "分享页反代未启用:请在 application.yaml 里配置 app.share-proxy.enabled=true 与 upstream-base"));
            return;
        }

        // 走到这里说明 AuthInterceptor 已经认过身份(请求头 / ?token= / Cookie 三者之一)
        LoginUser user = UserContext.require();

        byte[] rawBody = readBody(request, config.maxRequestBytes());
        if (rawBody == null) {
            responseWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE, ApiResponse.fail(
                    HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "请求体超过上限 " + config.maxRequestBytes() + " 字节"));
            return;
        }

        // 尽力解析,失败就当作"看不懂",退化成纯透传(不解析也不改写)
        JsonNode root = tryParse(rawBody);
        String question = extractQuestion(root);
        if (question.isEmpty()) {
            // 交互节点续跑、图文消息等场景可能取不到文本。这里不报错:
            // 内容表 question 是 NOT NULL,留空串即可,关键是每日计数不能因此漏掉。
            log.debug("未能从请求体解析出用户问题,按空问题记账 [{}]", request.getRequestURI());
        }
        String chatId = resolveChatId(root, user);
        byte[] outgoing = rewriteChatId(rawBody, root, chatId, config);

        // ---------- 0. 每日上限:满了直接拦,不扣总额度 ----------
        DailyLimitService.Permit permit;
        try {
            permit = dailyLimitService.acquire(channelType, user.id());
        } catch (BizException limited) {
            record(user, chatId, question, null, ChatUsage.empty(), 0,
                    RecordStatus.REJECTED, limited.getMessage(), 0L, config);
            throw limited;
        }

        // 名额要占到记录落库之后再释放,否则并发请求会看到"少算一次"
        try (permit) {
            forward(config, request, outgoing, response, user, chatId, question);
        }
    }

    private void forward(AppProperties.ShareProxy config, HttpServletRequest request, byte[] body,
                         HttpServletResponse response, LoginUser user, String chatId, String question)
            throws IOException {
        // ---------- 1. 前置拦截:总额度不足 ----------
        int reserved = 0;
        if (quotaService.enabled()) {
            if (quotaService.deductMode() == AppProperties.DeductMode.REQUEST) {
                if (!quotaService.tryConsume(user.id(), 1)) {
                    BizException failure = quotaService.explainConsumeFailure(user.id());
                    record(user, chatId, question, null, ChatUsage.empty(), 0,
                            RecordStatus.REJECTED, failure.getMessage(), 0L, config);
                    throw failure;
                }
                reserved = 1;
            } else {
                QuotaInfo before = quotaService.snapshot(user.id());
                if (!before.unlimited() && before.remaining() <= 0) {
                    BizException failure = BizException.quotaExhausted("调用次数已用完(" + before.used() + "/"
                            + before.total() + "),请联系管理员增加额度");
                    record(user, chatId, question, null, ChatUsage.empty(), 0,
                            RecordStatus.REJECTED, failure.getMessage(), 0L, config);
                    throw failure;
                }
            }
        }

        // ---------- 2. 透传到 FastGPT ----------
        String url = config.upstreamUrl(pathAndQuery(request));
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = buildUpstreamRequest(request, url, body, config);
        } catch (IllegalArgumentException ex) {
            record(user, chatId, question, null, ChatUsage.empty(), reserved,
                    RecordStatus.FAILED, "FastGPT 地址不合法: " + url, 0L, config);
            throw BizException.upstream("FastGPT 地址不合法: " + url);
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            int cost = refundIfNeeded(user.id(), reserved);
            record(user, chatId, question, null, ChatUsage.empty(), cost,
                    RecordStatus.FAILED, "调用 FastGPT 被中断", System.currentTimeMillis() - startedAt, config);
            throw BizException.upstream("调用知识库服务被中断");
        } catch (IOException ex) {
            int cost = refundIfNeeded(user.id(), reserved);
            record(user, chatId, question, null, ChatUsage.empty(), cost,
                    RecordStatus.FAILED, "无法连接 FastGPT(" + url + "): " + ex.getMessage(),
                    System.currentTimeMillis() - startedAt, config);
            throw BizException.upstream("无法连接知识库服务: " + ex.getMessage());
        }

        int status = upstream.statusCode();
        String contentType = upstream.headers().firstValue("Content-Type").orElse("");

        // ---------- 3. 上游报错:退还额度,错误原样透传 ----------
        if (status < 200 || status >= 300) {
            String errorBody = readQuietly(upstream.body());
            log.warn("分享页上游返回非 2xx: status={} url={} body={}", status, url, abbreviate(errorBody));
            int cost = refundIfNeeded(user.id(), reserved);
            record(user, chatId, question, null, ChatUsage.empty(), cost, RecordStatus.FAILED,
                    "上游 " + status + ": " + abbreviate(errorBody), System.currentTimeMillis() - startedAt, config);
            writeRaw(response, status, contentType, errorBody);
            return;
        }

        // ---------- 4. 成功:透传响应 + 旁路记账 ----------
        response.setHeader("X-Quota-Remaining", remainingHeader(user.id()));
        StreamSink sink = new StreamSink();
        try {
            if (contentType.toLowerCase().contains("text/event-stream")) {
                streamThrough(upstream.body(), response, contentType, sink);
            } else {
                readWhole(upstream.body(), response, contentType, sink);
            }
        } finally {
            closeQuietly(upstream.body());
        }

        int cost = reserved;
        if (quotaService.enabled() && quotaService.deductMode() == AppProperties.DeductMode.TOKEN) {
            cost = Math.max(1, (int) Math.ceil(
                    sink.usage().totalTokens() / (double) quotaService.tokensPerUnit()));
            if (!quotaService.tryConsume(user.id(), cost)) {
                // 上游已经返回内容,这里只能记账告警,不能反过来拒绝用户
                log.warn("用户 {} 本次应扣 {} 次但余额不足,已超额放行(上游已返回结果)", user.id(), cost);
            }
        }
        record(user, chatId, question, sink.answer(), sink.usage(), cost, RecordStatus.SUCCESS, null,
                System.currentTimeMillis() - startedAt, config);
    }

    // ------------------------------------------------------------ 上游请求

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, String url, byte[] body,
                                             AppProperties.ShareProxy config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", acceptHeader(request))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        // 刻意不透传 Authorization:
        //   分享链接本来就是"免登录"的,它靠请求体里的 shareId 鉴权,不需要这个头;
        //   而如果请求带的是我们自己的 JWT(例如有人直接拿令牌打这个路径),
        //   透传就等于把我们的登录凭证送给上游。两边权衡,不发最安全。
        //
        // 透传 Cookie,但必须把"我们自己的登录 Cookie"摘掉 ——
        // 同理,那是我们的 JWT,没有理由送给上游。
        // 其余 Cookie(FastGPT 自己种下的会话)原样转发,这样代理到我们源上之后
        // FastGPT 侧的登录态依然可用。
        String cookies = stripOurCookie(request.getHeader("Cookie"));
        if (!cookies.isBlank()) {
            builder.header("Cookie", cookies);
        }
        return builder.build();
    }

    private static String acceptHeader(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept == null || accept.isBlank() ? "*/*" : accept;
    }

    /** 从 Cookie 头里剔除我们自己的登录 Cookie,其余原样保留。 */
    static String stripOurCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = eq < 0 ? trimmed : trimmed.substring(0, eq);
            if (AuthCookie.NAME.equals(name.trim())) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append("; ");
            }
            kept.append(trimmed);
        }
        return kept.toString();
    }

    private static String pathAndQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    // ------------------------------------------------------------ 响应透传

    /** 逐行原样转发 SSE,同时旁路收集回答与 usage。 */
    private void streamThrough(InputStream upstreamBody, HttpServletResponse response, String contentType,
                               StreamSink sink) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(contentType.isBlank() ? "text/event-stream;charset=UTF-8" : contentType);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        // 让 Nginx 之类的反向代理不要缓冲 SSE
        response.setHeader("X-Accel-Buffering", "no");

        OutputStream clientOut = response.getOutputStream();
        // 按字节缓冲一整行:SSE 是 UTF-8,逐字节转 char 会把中文拆坏
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        try (InputStream in = new BufferedInputStream(upstreamBody)) {
            int b;
            while ((b = in.read()) != -1) {
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                byte[] bytes = line.toByteArray();
                line.reset();
                // 原样回写整行(含可能的 \r)+ \n,SSE 靠空行分隔事件
                clientOut.write(bytes);
                clientOut.write('\n');
                clientOut.flush();

                String text = new String(bytes, StandardCharsets.UTF_8);
                String normalized = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                if (!normalized.isEmpty()) {
                    collectSseLine(normalized, sink);
                }
            }
            if (line.size() > 0) {
                byte[] tail = line.toByteArray();
                clientOut.write(tail);
                collectSseLine(new String(tail, StandardCharsets.UTF_8), sink);
            }
        } catch (IOException ex) {
            // 客户端提前断开或上游中断:保留已收集到的部分,
            // 让调用方仍然能落一条记录,而不是整段丢账。
            log.warn("分享页流式转发中断(已收集回答 {} 字): {}", sink.answerLength(), ex.getMessage());
        }
        try {
            clientOut.flush();
        } catch (IOException ex) {
            log.debug("冲刷响应失败(客户端可能已断开): {}", ex.getMessage());
        }
    }

    /** 非流式:读完整包,原样写回。 */
    private void readWhole(InputStream upstreamBody, HttpServletResponse response, String contentType,
                           StreamSink sink) throws IOException {
        String raw = new String(upstreamBody.readAllBytes(), StandardCharsets.UTF_8);
        collectJsonBody(raw, sink);
        writeRaw(response, HttpServletResponse.SC_OK,
                contentType.isBlank() ? "application/json;charset=UTF-8" : contentType, raw);
    }

    /** 从一行 SSE 里旁路提取回答增量与 usage,不改动透传内容。 */
    private void collectSseLine(String line, StreamSink sink) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || !trimmed.startsWith("data:")) {
            return;
        }
        String payload = trimmed.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        JsonNode node = tryParse(payload.getBytes(StandardCharsets.UTF_8));
        if (node == null) {
            return;
        }
        JsonNode choices = node.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode delta = choices.path(0).path("delta").path("content");
            if (delta.isTextual()) {
                sink.appendAnswer(delta.asText());
            }
        }
        sink.mergeUsage(node.path("usage"));
    }

    private void collectJsonBody(String raw, StreamSink sink) {
        JsonNode root = tryParse(raw.getBytes(StandardCharsets.UTF_8));
        if (root == null) {
            return;
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (!content.isEmpty()) {
            sink.appendAnswer(content);
        }
        sink.mergeUsage(root.path("usage"));
    }

    private static void writeRaw(HttpServletResponse response, int status, String contentType, String body)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(contentType.isBlank() ? "application/json;charset=UTF-8" : contentType);
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(payload);
        response.getOutputStream().flush();
    }

    // ------------------------------------------------------------ 请求体处理

    /** 读取请求体,超过上限返回 null。 */
    private byte[] readBody(HttpServletRequest request, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        try (InputStream in = request.getInputStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    private JsonNode tryParse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        // 形如 "data: {...}" 的 SSE 行,这里已经剥过前缀;剩下的直接当 JSON 解
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 取最后一条 user 消息的文本作为"问题"。
     *
     * <p>找不到就返回空串,而不是报错 —— 内容表的 question 是 NOT NULL,
     * 留空串能让记录照常落库,每日计数也就不会漏。</p>
     */
    private String extractQuestion(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if (!"user".equalsIgnoreCase(message.path("role").asText(""))) {
                continue;
            }
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                String text = content.asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
            if (content.isArray()) {
                // 图文/文件消息:content 是分片数组
                StringBuilder joined = new StringBuilder();
                for (JsonNode part : content) {
                    if (part.isTextual()) {
                        joined.append(part.asText(""));
                    } else if (part.path("text").isTextual()) {
                        joined.append(part.path("text").asText(""));
                    }
                }
                String text = joined.toString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /** 会话 ID:统一前缀 {@code u<用户ID>-<链路>-},让每个账号在 FastGPT 侧拥有独立会话。 */
    private String resolveChatId(JsonNode root, LoginUser user) {
        String raw = root == null ? "" : root.path("chatId").asText("");
        String sanitized = raw.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty()) {
            sanitized = randomChatId();
        }
        String candidate = "u" + user.id() + "-" + channelType.pathKey() + "-" + sanitized;
        return candidate.length() <= MAX_CHAT_ID_LENGTH ? candidate : candidate.substring(0, MAX_CHAT_ID_LENGTH);
    }

    /**
     * 把请求体里的 chatId 换成加了账号前缀的版本。
     *
     * <p>只有在原请求确实带了 chatId 时才改写:没带说明会话 ID 由 FastGPT 随机生成,
     * 随机值本身就不会跨账号撞车,我们不该凭空塞一个进去改变它的行为。</p>
     *
     * <p>解析失败或改写失败一律退回原始字节 —— 透传的可靠性优先于隔离。</p>
     */
    private byte[] rewriteChatId(byte[] raw, JsonNode root, String chatId, AppProperties.ShareProxy config) {
        if (!config.namespaceChatId() || root == null || !root.isObject() || !root.path("chatId").isTextual()) {
            return raw;
        }
        try {
            ObjectNode object = (ObjectNode) root;
            object.put("chatId", chatId);
            return objectMapper.writeValueAsBytes(object);
        } catch (Exception ex) {
            log.warn("改写 chatId 失败,按原始请求体透传: {}", ex.getMessage());
            return raw;
        }
    }

    private static String randomChatId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    // ------------------------------------------------------------ 记账

    private void record(LoginUser user, String chatId, String question, String answer, ChatUsage usage,
                        int costQuota, RecordStatus status, String errorMsg, long durationMs,
                        AppProperties.ShareProxy config) {
        String trimmedQuestion = question == null ? "" : question;

        if (config.storeContent()) {
            QuestionRecord content = new QuestionRecord();
            content.setUserId(user.id());
            content.setUsername(user.username());
            content.setSessionId(chatId);
            content.setQuestion(trimmedQuestion);
            content.setAnswer(answer);
            // 分享链接没有 appId,留空
            content.setAppId(null);
            content.setPromptTokens(usage.promptTokens());
            content.setCompletionTokens(usage.completionTokens());
            content.setTotalTokens(usage.totalTokens());
            content.setCostQuota(costQuota);
            content.setStatus(status.name());
            content.setErrorMsg(abbreviate(errorMsg));
            content.setDurationMs(durationMs);
            recordService.saveQuietly(channelType, content);
        }

        ClientInfoResolver.ClientInfo client = clientInfoResolver.resolve();
        UsageLog entry = new UsageLog();
        entry.setUserId(user.id());
        entry.setUsername(user.username());
        entry.setChannel(channelType.pathKey());
        entry.setSessionId(chatId);
        entry.setAppId(null);
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

    /** 上游失败时退还已扣次数,返回实际保留的扣减数(退还后为 0)。 */
    private int refundIfNeeded(long userId, int reserved) {
        if (reserved > 0 && quotaService.refundOnFailure()) {
            quotaService.refund(userId, reserved);
            return 0;
        }
        return reserved;
    }

    private String remainingHeader(long userId) {
        QuotaInfo after = quotaService.snapshot(userId);
        return after.unlimited() ? "-1" : String.valueOf(after.remaining());
    }

    // ------------------------------------------------------------ 工具

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= ERROR_TEXT_LIMIT ? single : single.substring(0, ERROR_TEXT_LIMIT) + "...";
    }

    private String readQuietly(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }

    /** 旁路收集到的回答与用量。 */
    private static final class StreamSink {
        private final StringBuilder answer = new StringBuilder();
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        void appendAnswer(String text) {
            if (text != null && !text.isEmpty()) {
                answer.append(text);
            }
        }

        void mergeUsage(JsonNode usage) {
            if (usage == null || !usage.isObject()) {
                return;
            }
            if (usage.hasNonNull("prompt_tokens")) {
                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
            }
            if (usage.hasNonNull("completion_tokens")) {
                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
            }
            if (usage.hasNonNull("total_tokens")) {
                totalTokens = usage.path("total_tokens").asInt(totalTokens);
            }
        }

        String answer() {
            return answer.length() == 0 ? null : answer.toString();
        }

        int answerLength() {
            return answer.length();
        }

        ChatUsage usage() {
            int total = totalTokens == 0 ? promptTokens + completionTokens : totalTokens;
            return new ChatUsage(promptTokens, completionTokens, total);
        }
    }
}
