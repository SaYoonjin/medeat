package com.medeat.chatbot.dto;

import java.time.Instant;

public class ChatSource {

    private final String provider;
    private final String title;
    private final Long itemSeq;
    private final String url;
    private final Instant retrievedAt;

    public ChatSource(String provider, String title, Long itemSeq, String url, Instant retrievedAt) {
        this.provider = provider;
        this.title = title;
        this.itemSeq = itemSeq;
        this.url = url;
        this.retrievedAt = retrievedAt;
    }

    public String getProvider() {
        return provider;
    }

    public String getTitle() {
        return title;
    }

    public Long getItemSeq() {
        return itemSeq;
    }

    public String getUrl() {
        return url;
    }

    public Instant getRetrievedAt() {
        return retrievedAt;
    }
}
