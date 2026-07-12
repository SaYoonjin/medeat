package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;

public record RagVectorSearchResult(
        String documentId,
        String content,
        Long itemSeq,
        String drugName,
        DrugInfoSection sectionType,
        int documentVersion,
        Long ragDocumentId,
        Long ragChunkId
) {
}
