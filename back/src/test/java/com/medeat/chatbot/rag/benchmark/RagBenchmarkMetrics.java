package com.medeat.chatbot.rag.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagBenchmarkMetrics {

    private RagBenchmarkMetrics() {
    }

    static RagBenchmarkSummary summarize(
            String datasetName,
            List<RagBenchmarkCaseResult> results,
            List<Integer> recallAt
    ) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Benchmark results must not be empty");
        }

        Map<Integer, Double> recall = new LinkedHashMap<>();
        for (int k : recallAt) {
            recall.put(k, results.stream()
                    .mapToDouble(result -> recallAt(result.relevantRanks(), k))
                    .average()
                    .orElse(0.0));
        }

        double mrr = results.stream()
                .mapToDouble(result -> reciprocalRank(result.relevantRanks()))
                .average()
                .orElse(0.0);
        List<Long> latencies = results.stream()
                .flatMap(result -> result.latencySamplesMillis().stream())
                .sorted()
                .toList();

        return new RagBenchmarkSummary(
                datasetName,
                results.size(),
                recall,
                mrr,
                latencies.stream().mapToLong(Long::longValue).average().orElse(0.0),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                List.copyOf(results)
        );
    }

    static double recallAt(List<Integer> relevantRanks, int k) {
        if (relevantRanks.isEmpty()) {
            return 0.0;
        }
        long retrievedRelevant = relevantRanks.stream()
                .filter(rank -> rank > 0 && rank <= k)
                .count();
        return (double) retrievedRelevant / relevantRanks.size();
    }

    static double reciprocalRank(List<Integer> relevantRanks) {
        return relevantRanks.stream()
                .filter(rank -> rank > 0)
                .min(Integer::compareTo)
                .map(rank -> 1.0 / rank)
                .orElse(0.0);
    }

    static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        List<Long> values = new ArrayList<>(sortedValues);
        Collections.sort(values);
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }
}
