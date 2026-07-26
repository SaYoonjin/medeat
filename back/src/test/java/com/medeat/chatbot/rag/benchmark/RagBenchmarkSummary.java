package com.medeat.chatbot.rag.benchmark;

import java.util.List;
import java.util.Map;

public record RagBenchmarkSummary(
        String datasetName,
        int caseCount,
        Map<Integer, Double> recallAt,
        double meanReciprocalRank,
        double averageLatencyMillis,
        long p50LatencyMillis,
        long p95LatencyMillis,
        long p99LatencyMillis,
        List<RagBenchmarkCaseResult> cases
) {
}
