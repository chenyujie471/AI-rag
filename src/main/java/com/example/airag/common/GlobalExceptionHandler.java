package com.example.airag.common;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理:把异常统一转成 {@link ApiResponse} JSON。
 *
 * <p>注意:如果响应已经开始写出(例如流式对话已经 flush 过),则不再尝试写 JSON,只记录日志。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final JsonResponseWriter responseWriter;

    public GlobalExceptionHandler(JsonResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(BizException.class)
    public void handleBizException(BizException ex, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            log.warn("响应已提交,无法返回业务错误: {}", ex.getMessage());
            return;
        }
        HttpStatus status = HttpStatus.resolve(ex.getHttpStatus());
        responseWriter.write(response, status == null ? HttpStatus.BAD_REQUEST : status,
                ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleNotReadable(HttpMessageNotReadableException ex, HttpServletResponse response) throws IOException {
        log.debug("请求体解析失败: {}", ex.getMessage());
        responseWriter.write(response, HttpStatus.BAD_REQUEST,
                ApiResponse.fail(ErrorCode.BAD_REQUEST, "请求体格式错误,请检查 JSON 内容"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public void handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletResponse response) throws IOException {
        responseWriter.write(response, HttpStatus.BAD_REQUEST,
                ApiResponse.fail(ErrorCode.BAD_REQUEST, "参数 " + ex.getName() + " 取值非法"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResource(NoResourceFoundException ex, HttpServletResponse response) throws IOException {
        responseWriter.write(response, HttpStatus.NOT_FOUND,
                ApiResponse.fail(ErrorCode.BAD_REQUEST, "接口不存在: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("未预期的服务端异常", ex);
        if (response.isCommitted()) {
            return;
        }
        responseWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "服务内部异常,请稍后重试"));
    }
}
