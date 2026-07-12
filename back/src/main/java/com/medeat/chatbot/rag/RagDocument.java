package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;

import java.time.LocalDateTime;

public record RagDocument(
        Long id,
        Long itemSeq,
        String drugName,
        DrugInfoSection sectionType,
        String content,
        String contentHash,
        int documentVersion,
        String source,
        LocalDateTime fetchedAt,
        RagDocumentLifecycleStatus lifecycleStatus
) {
}
