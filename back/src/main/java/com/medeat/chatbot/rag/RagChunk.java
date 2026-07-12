package com.medeat.chatbot.rag;

public record RagChunk(
        int index,
        String content,
        String contentHash
) {
}
