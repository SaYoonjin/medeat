package com.medeat.chatbot.rag;

public record RagDocumentIndexingResult(
        int scannedSections,
        int skippedSections,
        int createdDocuments,
        int createdChunks,
        int createdJobs
) {
    public static RagDocumentIndexingResult empty() {
        return new RagDocumentIndexingResult(0, 0, 0, 0, 0);
    }

    public RagDocumentIndexingResult plus(RagDocumentIndexingResult other) {
        return new RagDocumentIndexingResult(
                scannedSections + other.scannedSections,
                skippedSections + other.skippedSections,
                createdDocuments + other.createdDocuments,
                createdChunks + other.createdChunks,
                createdJobs + other.createdJobs
        );
    }
}
