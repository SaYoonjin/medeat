package com.medeat.chatbot.rag.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medeat.chatbot.rag.RagDocument;
import com.medeat.chatbot.rag.RagDocumentDao;
import com.medeat.chatbot.rag.RagVectorSearchResult;
import com.medeat.chatbot.rag.RagVectorStoreService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("rag-benchmark")
@SpringBootTest(properties = "medeat.rag.enabled=true")
class RagRetrievalBenchmarkTest {

    private static final String DEFAULT_DATASET =
            "rag-benchmark/evaluation-dataset.template.json";
    private static final List<Integer> RECALL_AT = List.of(1, 3, 5);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagDocumentDao ragDocumentDao;

    @Autowired
    private RagVectorStoreService vectorStoreService;

    @Test
    void measureRetrievalQualityAndLatency() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("medeat.rag.benchmark.enabled"),
                "Run with -Dmedeat.rag.benchmark.enabled=true"
        );

        RagBenchmarkDataset dataset = loadDataset();
        assertThat(dataset.cases()).isNotEmpty();
        dataset.cases().forEach(RagBenchmarkDataset.RagBenchmarkCase::validate);

        int warmupCount = positiveIntProperty("medeat.rag.benchmark.warmups", 2);
        int repetitions = positiveIntProperty("medeat.rag.benchmark.repetitions", 5);
        int topK = positiveIntProperty("medeat.rag.benchmark.top-k", 5);
        if (topK < RECALL_AT.stream().mapToInt(Integer::intValue).max().orElse(1)) {
            throw new IllegalArgumentException(
                    "medeat.rag.benchmark.top-k must be at least the largest Recall@K value"
            );
        }
        List<RagBenchmarkCaseResult> results = new ArrayList<>();

        for (RagBenchmarkDataset.RagBenchmarkCase benchmarkCase : dataset.cases()) {
            int documentVersion = resolveDocumentVersion(benchmarkCase);
            for (int index = 0; index < warmupCount; index++) {
                search(benchmarkCase, documentVersion, topK);
            }

            List<Long> latencySamples = new ArrayList<>();
            List<RagVectorSearchResult> retrieved = List.of();
            for (int index = 0; index < repetitions; index++) {
                long startedAt = System.nanoTime();
                retrieved = search(benchmarkCase, documentVersion, topK);
                latencySamples.add(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            }

            results.add(toResult(benchmarkCase, documentVersion, retrieved, latencySamples));
        }

        RagBenchmarkSummary summary = RagBenchmarkMetrics.summarize(
                dataset.name(),
                results,
                RECALL_AT
        );
        writeReports(summary);

        System.out.println(toMarkdown(summary));
    }

    private RagBenchmarkDataset loadDataset() throws IOException {
        String datasetPath = System.getProperty("medeat.rag.benchmark.dataset");
        if (datasetPath != null && !datasetPath.isBlank()) {
            return objectMapper.readValue(Path.of(datasetPath).toFile(), RagBenchmarkDataset.class);
        }
        try (InputStream input = new ClassPathResource(DEFAULT_DATASET).getInputStream()) {
            return objectMapper.readValue(input, RagBenchmarkDataset.class);
        }
    }

    private int resolveDocumentVersion(RagBenchmarkDataset.RagBenchmarkCase benchmarkCase) {
        if (benchmarkCase.documentVersion() != null) {
            return benchmarkCase.documentVersion();
        }
        return ragDocumentDao.findActiveDocument(
                        benchmarkCase.itemSeq(),
                        benchmarkCase.sectionType()
                )
                .map(RagDocument::documentVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "No ACTIVE RAG document for benchmark case: " + benchmarkCase.id()
                ));
    }

    private List<RagVectorSearchResult> search(
            RagBenchmarkDataset.RagBenchmarkCase benchmarkCase,
            int documentVersion,
            int topK
    ) {
        return vectorStoreService.searchSimilarChunks(
                benchmarkCase.query(),
                benchmarkCase.itemSeq(),
                benchmarkCase.sectionType(),
                documentVersion,
                topK
        );
    }

    private RagBenchmarkCaseResult toResult(
            RagBenchmarkDataset.RagBenchmarkCase benchmarkCase,
            int documentVersion,
            List<RagVectorSearchResult> retrieved,
            List<Long> latencySamples
    ) {
        List<Integer> relevantRanks = new ArrayList<>();
        for (Long relevantChunkId : benchmarkCase.relevantChunkIds()) {
            relevantRanks.add(rankOfChunk(retrieved, relevantChunkId));
        }
        for (String expectedText : benchmarkCase.relevantTextContains()) {
            relevantRanks.add(rankOfText(retrieved, expectedText));
        }

        long averageLatency = Math.round(
                latencySamples.stream().mapToLong(Long::longValue).average().orElse(0.0)
        );
        return new RagBenchmarkCaseResult(
                benchmarkCase.id(),
                benchmarkCase.query(),
                benchmarkCase.itemSeq(),
                benchmarkCase.sectionType().name(),
                documentVersion,
                averageLatency,
                List.copyOf(latencySamples),
                List.copyOf(relevantRanks),
                retrieved.stream().map(RagVectorSearchResult::ragChunkId).toList()
        );
    }

    private int rankOfChunk(List<RagVectorSearchResult> retrieved, Long relevantChunkId) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (relevantChunkId.equals(retrieved.get(index).ragChunkId())) {
                return index + 1;
            }
        }
        return 0;
    }

    private int rankOfText(List<RagVectorSearchResult> retrieved, String expectedText) {
        String normalizedExpected = normalize(expectedText);
        for (int index = 0; index < retrieved.size(); index++) {
            if (normalize(retrieved.get(index).content()).contains(normalizedExpected)) {
                return index + 1;
            }
        }
        return 0;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private int positiveIntProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private void writeReports(RagBenchmarkSummary summary) throws IOException {
        Path outputDirectory = Path.of(System.getProperty(
                "medeat.rag.benchmark.output-dir",
                "target/rag-benchmark"
        ));
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDirectory.resolve("result.json").toFile(), summary);
        Files.writeString(
                outputDirectory.resolve("result.md"),
                toMarkdown(summary),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private String toMarkdown(RagBenchmarkSummary summary) {
        StringBuilder report = new StringBuilder();
        report.append("# RAG 검색 벤치마크 결과\n\n")
                .append("- 평가셋: ").append(summary.datasetName()).append('\n')
                .append("- 평가 질문 수: ").append(summary.caseCount()).append('\n');
        summary.recallAt().forEach((k, value) -> report
                .append("- Recall@").append(k).append(": ")
                .append(String.format(Locale.ROOT, "%.4f", value)).append('\n'));
        report.append("- MRR: ")
                .append(String.format(Locale.ROOT, "%.4f", summary.meanReciprocalRank()))
                .append('\n')
                .append("- 평균 검색시간: ")
                .append(String.format(Locale.ROOT, "%.2f ms", summary.averageLatencyMillis()))
                .append('\n')
                .append("- P50 / P95 / P99: ")
                .append(summary.p50LatencyMillis()).append(" / ")
                .append(summary.p95LatencyMillis()).append(" / ")
                .append(summary.p99LatencyMillis()).append(" ms\n\n")
                .append("| ID | Section | Version | 평균(ms) | 정답 순위 | 검색 Chunk |\n")
                .append("|---|---|---:|---:|---|---|\n");
        for (RagBenchmarkCaseResult result : summary.cases()) {
            report.append("| ").append(result.id())
                    .append(" | ").append(result.sectionType())
                    .append(" | ").append(result.documentVersion())
                    .append(" | ").append(result.latencyMillis())
                    .append(" | ").append(result.relevantRanks())
                    .append(" | ").append(result.retrievedChunkIds())
                    .append(" |\n");
        }
        return report.toString();
    }
}
