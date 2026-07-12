package com.medeat.chatbot.dto;

import java.time.Instant;
import java.util.List;

public class ChatResponse {

    private String answer;
    private List<ChatSource> sources;
    private Instant retrievedAt;

    public ChatResponse(String answer) {
        this(answer, List.of(), Instant.now());
    }

    public ChatResponse(String answer, List<ChatSource> sources, Instant retrievedAt) {
        this.answer = answer;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.retrievedAt = retrievedAt;
    }

    public String getAnswer() {
        return answer;
    }

    public List<ChatSource> getSources() {
        return sources;
    }

    public Instant getRetrievedAt() {
        return retrievedAt;
    }
}
