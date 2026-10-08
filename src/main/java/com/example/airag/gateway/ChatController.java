package com.example.airag.gateway;

import java.io.IOException;

import com.example.airag.record.RecordType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话网关入口。前端只请求这两个地址,不再直连 FastGPT。
 *
 * <ul>
 *   <li>{@code POST /api/chat/faq} —— 平台 FAQ 知识库链路(记录到 ai_question_record)</li>
 *   <li>{@code POST /api/chat/after-sale} —— 售后知识库链路(记录到 after_sale_question_record)</li>
 * </ul>
 *
 * <p>返回 {@code text/event-stream} 时前端按 SSE 增量渲染;返回 JSON 时字段见 {@link ChatResponse}。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/faq")
    public void faq(@RequestBody ChatRequest request, HttpServletResponse response) throws IOException {
        chatService.chat(RecordType.AI_QUESTION, request, response);
    }

    @PostMapping("/after-sale")
    public void afterSale(@RequestBody ChatRequest request, HttpServletResponse response) throws IOException {
        chatService.chat(RecordType.AFTER_SALE, request, response);
    }
}
