package com.example.airag.gateway;

import java.util.List;
import java.util.Map;

import com.example.airag.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

/**
 * FastGPT 对接客户端(基于 JDK HttpClient,零额外依赖)。
 *
 * <p>调用的是 FastGPT 的 OpenAI 兼容接口:`POST {apiUrl}`,请求头带
 * {@code Authorization: Bearer <apiKey>}。详见
 * <a href="https://doc.fastgpt.cn/en/openapi/chat">FastGPT Chat API</a>。</p>
 *
 * <p>两种返回模式:</p>
 * <ul>
 *   <li>{@code stream=true}:把上游 SSE 逐行原样透传给浏览器,同时旁路解析出回答文本与 usage;</li>
 *   <li>{@code stream=false}:读完整包 JSON,解析 choices[0].message.content 与 usage。</li>
 * </ul>
 *
 * <p><b>配置缺失时直接报 503,绝不返回任何假内容。</b>以前有一个"未配置就走本地模拟回复"的
 * 降级分支,后果是配置没生效时页面照样能问、只是答的不是真实内容,把真正的问题藏了很久。
 * 那个分支已经删掉:宁可报错,也不要静默降级。</p>
 */
@Component
public class FastGptClient {

    private static final Logger log = LoggerFactory.getLogger(FastGptClient.class);
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";
    private static final int ERROR_TEXT_LIMIT = 300;

    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final HttpClient httpClient;

    public FastGptClient(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.gateway().connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 调用一个 FastGPT 应用。
     *
     * <p>成功且 stream=true 时,响应体已经被写出一部分;失败时保证没有写过任何字节(除非 clientCommitted=true)。</p>
     */
    public FastGptResult call(AppProperties.FastGptApp app, String chatId, String question, boolean stream,
                              HttpServletResponse response) throws IOException {
        if (!app.configured()) {
            String hint = missingConfigHint(app);
            log.error("FastGPT 应用未配置完整,已拒绝调用(不再返回模拟回复): {}", hint);
            // ChatService 统一把失败写成 502,这里的状态码只是给自己看
            return FastGptResult.fail(502, hint, false);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!app.appId().isBlank()) {
            payload.put("appId", app.appId());
        }
        payload.put("chatId", chatId);
        payload.put("stream", stream);
        payload.put("detail", false);
        payload.put("messages", List.of(Map.of("role", "user", "content", question)));

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return FastGptResult.fail(500, "构造上游请求体失败: " + ex.getMessage(), false);
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(app.apiUrl()))
                    .timeout(Duration.ofSeconds(properties.gateway().readTimeoutSeconds()))
                    .header("Authorization", "Bearer " + app.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", stream ? "text/event-stream" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            return FastGptResult.fail(500, "FastGPT 地址不合法: " + app.apiUrl(), false);
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FastGptResult.fail(504, "调用 FastGPT 被中断", false);
        } catch (IOException ex) {
            return FastGptResult.fail(502, "无法连接 FastGPT(" + app.apiUrl() + "): " + ex.getMessage(), false);
        }

        int status = upstream.statusCode();
        if (status < 200 || status >= 300) {
            String errorBody = readQuietly(upstream.body());
            log.warn("FastGPT 返回非 2xx: status={} chatId={} body={}", status, chatId, abbreviate(errorBody));
            return FastGptResult.fail(502, "FastGPT 返回错误(" + status + "): " + extractMessage(errorBody), false);
        }

        if (stream) {
            return streamThrough(upstream.body(), response);
        }
        return readWhole(upstream.body());
    }

    // -------------------------------------------------------------- 流式转发

    private FastGptResult streamThrough(InputStream upstreamBody, HttpServletResponse response) throws IOException {
        prepareSse(response);
        StringBuilder answer = new StringBuilder();
        int[] promptTokens = {0};
        int[] completionTokens = {0};
        int[] totalTokens = {0};
        OutputStream clientOut = response.getOutputStream();
        boolean completed = false;
        long startedAt = System.currentTimeMillis();
        long firstChunkAt = 0L;
        int chunks = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(upstreamBody, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                clientOut.write(line.getBytes(StandardCharsets.UTF_8));
                clientOut.write('\n');
                clientOut.flush();
                int before = answer.length();
                collectSseLine(line, answer, promptTokens, completionTokens, totalTokens);
                if (answer.length() > before) {
                    chunks++;
                    if (firstChunkAt == 0L) {
                        firstChunkAt = System.currentTimeMillis();
                    }
                }
            }
            completed = true;
        } catch (IOException ex) {
            // 客户端提前断开或上游中断:保留已经拿到的部分内容
            log.warn("流式转发中断: {}", ex.getMessage());
        }
        logStreamSummary(completed, chunks, firstChunkAt == 0L ? -1L : firstChunkAt - startedAt,
                System.currentTimeMillis() - startedAt, answer.length());
        ChatUsage usage = new ChatUsage(promptTokens[0], completionTokens[0], totalTokens[0]);
        if (!completed && answer.length() == 0) {
            return FastGptResult.fail(502, "FastGPT 流式响应中断且未返回任何内容", true);
        }
        return FastGptResult.ok(answer.toString(), usage, true);
    }

    /** 从一行 SSE 里旁路提取回答增量与 usage,不改动透传内容。 */
    private void collectSseLine(String line, StringBuilder answer, int[] promptTokens, int[] completionTokens,
                                int[] totalTokens) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || !trimmed.startsWith(SSE_DATA_PREFIX)) {
            return;
        }
        String payload = trimmed.substring(SSE_DATA_PREFIX.length()).trim();
        if (payload.isEmpty() || SSE_DONE.equals(payload)) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception ex) {
            return;
        }
        JsonNode choices = node.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode delta = choices.path(0).path("delta").path("content");
            if (delta.isTextual()) {
                answer.append(delta.asText());
            }
        }
        readUsage(node.path("usage"), promptTokens, completionTokens, totalTokens);
    }

    // -------------------------------------------------------------- 非流式

    private FastGptResult readWhole(InputStream upstreamBody) throws IOException {
        String raw = new String(upstreamBody.readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception ex) {
            return FastGptResult.fail(502, "无法解析 FastGPT 返回内容: " + abbreviate(raw), false);
        }
        String answer = root.path("choices").path(0).path("message").path("content").asText("");
        int[] promptTokens = {0};
        int[] completionTokens = {0};
        int[] totalTokens = {0};
        readUsage(root.path("usage"), promptTokens, completionTokens, totalTokens);
        if (totalTokens[0] == 0) {
            totalTokens[0] = promptTokens[0] + completionTokens[0];
        }
        return FastGptResult.ok(answer, new ChatUsage(promptTokens[0], completionTokens[0], totalTokens[0]), false);
    }

    private void readUsage(JsonNode usage, int[] promptTokens, int[] completionTokens, int[] totalTokens) {
        if (usage == null || !usage.isObject()) {
            return;
        }
        if (usage.hasNonNull("prompt_tokens")) {
            promptTokens[0] = usage.path("prompt_tokens").asInt(promptTokens[0]);
        }
        if (usage.hasNonNull("completion_tokens")) {
            completionTokens[0] = usage.path("completion_tokens").asInt(completionTokens[0]);
        }
        if (usage.hasNonNull("total_tokens")) {
            totalTokens[0] = usage.path("total_tokens").asInt(totalTokens[0]);
        }
    }

    // ---------------------------------------------------------- 流式诊断

    /**
     * 打完一次流式转发的体检报告。
     *
     * <p>用来回答一个很实际的问题:<b>页面上逐字出现的效果,到底是 FastGPT 真在分片推送,
     * 还是它一次性把整段丢下来、靠前端打字机装出来的?</b> 这两种情况该改的地方完全不同 ——
     * 前者说明链路没问题,后者说明要去 FastGPT 那边调工作流节点(「指定回复」「代码运行」
     * 这类节点天然不分片,只有「AI 对话」节点才是真流式)。</p>
     *
     * @param chunks     上游有效分片数(含回答内容的 data 行)
     * @param firstChunk 从发起请求到收到第一段内容之间的毫秒数,-1 表示一段都没收到
     */
    private void logStreamSummary(boolean completed, int chunks, long firstChunk, long elapsed, int answerLength) {
        String verdict;
        if (chunks <= 0) {
            verdict = "上游一段内容都没给,回答是空的";
        } else if (chunks == 1) {
            verdict = "上游把整段一次性推下来了 —— 页面上的逐字效果来自前端打字机,不是真流式";
        } else if (chunks <= 3) {
            verdict = "上游只分了几段,基本等于一次性推送";
        } else {
            verdict = "上游是真流式推送";
        }
        log.info("流式转发{}: 上游分片 {} 个, 首片 {} ms, 总耗时 {} ms, 回答 {} 字 —— {}",
                completed ? "完成" : "中断", chunks,
                firstChunk < 0 ? "未收到" : firstChunk + " ms",
                elapsed, answerLength, verdict);
    }

    // ------------------------------------------------------- 配置缺失诊断

    /**
     * 拼出"到底缺哪一项"的提示。
     *
     * <p>这段话会同时进日志和返回给前端,所以刻意写得具体:配置缺失时最耗时的不是"知道没配对",
     * 而是"不知道缺的是哪一项"。</p>
     */
    private static String missingConfigHint(AppProperties.FastGptApp app) {
        StringBuilder missing = new StringBuilder();
        if (app.apiKey().isBlank()) {
            missing.append("api-key 为空");
        }
        if (app.apiUrl().isBlank()) {
            appendMissing(missing, "api-url 为空");
        } else if (app.apiUrl().contains("your-fastgpt-host")) {
            appendMissing(missing, "api-url 仍是占位符");
        }
        if (missing.length() == 0) {
            missing.append("配置不完整");
        }
        return "知识库服务未接通:" + missing
                + "。当前 api-url=" + (app.apiUrl().isBlank() ? "<空>" : app.apiUrl())
                + ", app-id=" + (app.appId().isBlank() ? "<空>" : app.appId())
                + " —— 请检查 application.yaml 的 app.gateway.* 是否生效(改完必须重启进程)";
    }

    private static void appendMissing(StringBuilder target, String item) {
        if (target.length() > 0) {
            target.append("; ");
        }
        target.append(item);
    }

    // -------------------------------------------------------------- 工具

    private void prepareSse(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        // 让 Nginx 之类的反向代理不要缓冲 SSE
        response.setHeader("X-Accel-Buffering", "no");
    }

    private String readQuietly(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "无返回内容";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String message = node.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // 不是 JSON,走截断
        }
        return abbreviate(body);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= ERROR_TEXT_LIMIT ? single : single.substring(0, ERROR_TEXT_LIMIT) + "...";
    }

    /** 供上层生成兜底会话 ID。 */
    public static String randomChatId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
