package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;

import java.time.LocalDateTime;

public record RagVectorChunk(
        Long chunkId,
        Long documentId,
        int chunkIndex,
        String content,
        Long itemSeq,
        String drugName,
        DrugInfoSection sectionType,
        int documentVersion,
        String source,
        LocalDateTime fetchedAt
) {
}
