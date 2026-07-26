package com.medeat.chatbot.rag.benchmark;

import com.medeat.medical.dto.DrugInfoSection;

import java.util.List;

public record RagBenchmarkDataset(
        String name,
        String description,
        List<RagBenchmarkCase> cases
) {
    public RagBenchmarkDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record RagBenchmarkCase(
            String id,
            String query,
            Long itemSeq,
            DrugInfoSection sectionType,
            Integer documentVersion,
            List<Long> relevantChunkIds,
            List<String> relevantTextContains,
            String notes
    ) {
        public RagBenchmarkCase {
            relevantChunkIds = relevantChunkIds == null ? List.of() : List.copyOf(relevantChunkIds);
            relevantTextContains = relevantTextContains == null
                    ? List.of()
                    : relevantTextContains.stream()
                            .filter(value -> value != null && !value.isBlank())
                            .toList();
        }

        public void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Benchmark case id must not be blank");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("Benchmark query must not be blank: " + id);
            }
            if (itemSeq == null || sectionType == null) {
                throw new IllegalArgumentException("itemSeq and sectionType are required: " + id);
            }
            if (relevantChunkIds.isEmpty() && relevantTextContains.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one relevance judgment is required: " + id
                );
            }
        }
    }
}
