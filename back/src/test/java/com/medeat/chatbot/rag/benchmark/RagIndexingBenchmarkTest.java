package com.medeat.chatbot.rag.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medeat.chatbot.rag.RagVectorStoreService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("rag-benchmark")
@SpringBootTest(properties = "medeat.rag.enabled=true")
class RagIndexingBenchmarkTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagVectorStoreService vectorStoreService;

    @Test
    void measurePendingChunkIndexingThroughput() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("medeat.rag.indexing-benchmark.enabled"),
                "Run with -Dmedeat.rag.indexing-benchmark.enabled=true"
        );

        int batchSize = positiveIntProperty("medeat.rag.indexing-benchmark.batch-size", 50);
        int maxChunks = positiveIntProperty("medeat.rag.indexing-benchmark.max-chunks", 1000);
        int indexedChunks = 0;
        int batchCount = 0;
        long startedAt = System.nanoTime();

        while (indexedChunks < maxChunks) {
            int limit = Math.min(batchSize, maxChunks - indexedChunks);
            int saved = vectorStoreService.savePendingChunks(limit);
            if (saved == 0) {
                break;
            }
            indexedChunks += saved;
            batchCount++;
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        double elapsedMillis = elapsedNanos / 1_000_000.0;
        double chunksPerSecond = elapsedNanos == 0
                ? 0.0
                : indexedChunks * 1_000_000_000.0 / elapsedNanos;

        assertThat(indexedChunks)
                .as("Seed PENDING RAG index jobs before running the indexing benchmark")
                .isPositive();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("batchSize", batchSize);
        report.put("maxChunks", maxChunks);
        report.put("indexedChunks", indexedChunks);
        report.put("batchCount", batchCount);
        report.put("elapsedMillis", elapsedMillis);
        report.put("chunksPerSecond", chunksPerSecond);
        writeReport(report);

        System.out.printf(
                Locale.ROOT,
                "RAG indexing: chunks=%d, batches=%d, elapsed=%.2f ms, throughput=%.2f chunks/s%n",
                indexedChunks,
                batchCount,
                elapsedMillis,
                chunksPerSecond
        );
    }

    private int positiveIntProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private void writeReport(Map<String, Object> report) throws Exception {
        Path outputDirectory = Path.of(System.getProperty(
                "medeat.rag.benchmark.output-dir",
                "target/rag-benchmark"
        ));
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDirectory.resolve("indexing-result.json").toFile(), report);
    }
}
