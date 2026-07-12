package com.medeat.chatbot.service;

import com.medeat.chatbot.dto.ChatSource;

import java.time.Instant;
import java.util.List;

public record ChatbotGroundingContext(
        String content,
        List<ChatSource> sources,
        Instant retrievedAt
) {
    public ChatbotGroundingContext {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
