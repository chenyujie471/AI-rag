package com.example.airag.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 在拦截器里直接写出 JSON 响应(此时还没走到 Controller,无法依赖 @ExceptionHandler)。
 *
 * <p>Servlet 规范规定 {@code getWriter()} 与 {@code getOutputStream()} 不能混用,
 * 而对话网关在流式模式下可能已经拿过 OutputStream。因此这里先试 OutputStream,
 * 失败再退回 Writer,保证两条路径都能正确输出。</p>
 */
@Component
public class JsonResponseWriter {

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private final ObjectMapper objectMapper;

    public JsonResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, ApiResponse<?> body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        byte[] payload = objectMapper.writeValueAsBytes(body);
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(JSON_CONTENT_TYPE);
        try {
            response.getOutputStream().write(payload);
            response.getOutputStream().flush();
        } catch (IllegalStateException alreadyUsingWriter) {
            response.getWriter().write(new String(payload, StandardCharsets.UTF_8));
            response.getWriter().flush();
        }
    }
}
