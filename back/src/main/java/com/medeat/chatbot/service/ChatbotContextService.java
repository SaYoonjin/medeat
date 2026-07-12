package com.medeat.chatbot.service;

public interface ChatbotContextService {

    ChatbotGroundingContext build(Long userId, String question);
}
