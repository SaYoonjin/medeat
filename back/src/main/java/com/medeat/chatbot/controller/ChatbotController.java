package com.medeat.chatbot.controller;

import com.medeat.chatbot.dto.ChatRequest;
import com.medeat.chatbot.dto.ChatResponse;
import com.medeat.chatbot.service.ChatbotService;
import com.medeat.common.web.SessionUserSupport;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chatbot")
public class ChatbotController {

    private final ChatbotService chatbotService;
    private final SessionUserSupport sessionUserSupport;

    public ChatbotController(ChatbotService chatbotService, SessionUserSupport sessionUserSupport) {
        this.chatbotService = chatbotService;
        this.sessionUserSupport = sessionUserSupport;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, HttpSession session) {
        Long userId = sessionUserSupport.getRequiredUser(session).getUserId();
        return chatbotService.ask(userId, req.getMessage().trim());
    }
}
