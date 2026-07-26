package com.medeat.chatbot.rag.benchmark;

import java.util.List;

public record RagBenchmarkCaseResult(
        String id,
        String query,
        Long itemSeq,
        String sectionType,
        int documentVersion,
        long latencyMillis,
        List<Long> latencySamplesMillis,
        List<Integer> relevantRanks,
        List<Long> retrievedChunkIds
) {
}
