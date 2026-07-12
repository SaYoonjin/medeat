package com.medeat.chatbot.service;

import com.medeat.chatbot.dto.ChatResponse;

public interface ChatbotService {
    ChatResponse ask(Long userId, String userMessage);
}
