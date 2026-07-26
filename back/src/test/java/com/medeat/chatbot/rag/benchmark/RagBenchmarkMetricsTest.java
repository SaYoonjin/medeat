package com.medeat.chatbot.rag.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagBenchmarkMetricsTest {

    @Test
    void calculatesRecallAndReciprocalRankIncludingMisses() {
        assertThat(RagBenchmarkMetrics.recallAt(List.of(1, 4, 0), 1))
                .isEqualTo(1.0 / 3.0);
        assertThat(RagBenchmarkMetrics.recallAt(List.of(1, 4, 0), 5))
                .isEqualTo(2.0 / 3.0);
        assertThat(RagBenchmarkMetrics.reciprocalRank(List.of(4, 2, 0)))
                .isEqualTo(0.5);
        assertThat(RagBenchmarkMetrics.reciprocalRank(List.of(0, 0)))
                .isZero();
    }

    @Test
    void calculatesNearestRankPercentiles() {
        List<Long> latencies = List.of(10L, 20L, 30L, 40L, 100L);

        assertThat(RagBenchmarkMetrics.percentile(latencies, 0.50)).isEqualTo(30L);
        assertThat(RagBenchmarkMetrics.percentile(latencies, 0.95)).isEqualTo(100L);
        assertThat(RagBenchmarkMetrics.percentile(latencies, 0.99)).isEqualTo(100L);
    }
}
