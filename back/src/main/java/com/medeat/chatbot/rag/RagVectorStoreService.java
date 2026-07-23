package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnBean(VectorStore.class)
public class RagVectorStoreService {

    private final VectorStore vectorStore;
    private final RagDocumentDao ragDocumentDao;
    private final int defaultTopK;
    private final Clock clock;

    public RagVectorStoreService(
            VectorStore vectorStore,
            RagDocumentDao ragDocumentDao,
            @Value("${medeat.rag.search.top-k:5}") int defaultTopK
    ) {
        this(vectorStore, ragDocumentDao, defaultTopK, Clock.systemDefaultZone());
    }

    RagVectorStoreService(
            VectorStore vectorStore,
            RagDocumentDao ragDocumentDao,
            int defaultTopK,
            Clock clock
    ) {
        this.vectorStore = vectorStore;
        this.ragDocumentDao = ragDocumentDao;
        this.defaultTopK = defaultTopK;
        this.clock = clock;
    }

    public int savePendingChunks(int limit) {
        List<RagVectorChunk> chunks = ragDocumentDao.findPendingVectorChunks(limit);
        if (chunks.isEmpty()) {
            return 0;
        }

        List<Document> documents = chunks.stream()
                .map(this::toDocument)
                .toList();

        vectorStore.add(documents);

        int savedCount = 0;
        LocalDateTime completedAt = LocalDateTime.now(clock);
        for (int index = 0; index < chunks.size(); index++) {
            RagVectorChunk chunk = chunks.get(index);
            Document document = documents.get(index);
            ragDocumentDao.updateChunkVectorId(chunk.chunkId(), document.getId());
            ragDocumentDao.markIndexJobCompleted(chunk.chunkId(), completedAt);
            ragDocumentDao.activateDocumentIfReady(chunk.documentId());
            savedCount++;
        }
        return savedCount;
    }

    public List<RagVectorSearchResult> searchSimilarChunks(
            String query,
            Long itemSeq,
            DrugInfoSection sectionType,
            int documentVersion
    ) {
        return searchSimilarChunks(query, itemSeq, sectionType, documentVersion, defaultTopK);
    }

    public List<RagVectorSearchResult> searchSimilarChunks(
            String query,
            Long itemSeq,
            DrugInfoSection sectionType,
            int documentVersion,
            int topK
    ) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression(itemSeq, sectionType, documentVersion))
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(this::toSearchResult)
                .toList();
    }

    public void deleteDocumentVersion(
            Long itemSeq,
            DrugInfoSection sectionType,
            int documentVersion
    ) {
        vectorStore.delete(filterExpression(itemSeq, sectionType, documentVersion));
    }

    private Document toDocument(RagVectorChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("itemSeq", String.valueOf(chunk.itemSeq()));
        metadata.put("drugName", chunk.drugName());
        metadata.put("sectionType", chunk.sectionType().name());
        metadata.put("documentVersion", String.valueOf(chunk.documentVersion()));
        metadata.put("ragDocumentId", String.valueOf(chunk.documentId()));
        metadata.put("ragChunkId", String.valueOf(chunk.chunkId()));
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("source", chunk.source());
        metadata.put("fetchedAt", chunk.fetchedAt() == null ? "" : chunk.fetchedAt().toString());
        return new Document(chunk.content(), metadata);
    }

    private RagVectorSearchResult toSearchResult(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RagVectorSearchResult(
                document.getId(),
                document.getText(),
                parseLong(metadata.get("itemSeq")),
                stringValue(metadata.get("drugName")),
                DrugInfoSection.valueOf(stringValue(metadata.get("sectionType"))),
                parseInt(metadata.get("documentVersion")),
                parseLong(metadata.get("ragDocumentId")),
                parseLong(metadata.get("ragChunkId"))
        );
    }

    private String filterExpression(Long itemSeq, DrugInfoSection sectionType, int documentVersion) {
        return "itemSeq == '" + escape(String.valueOf(itemSeq)) + "'"
                + " && sectionType == '" + escape(sectionType.name()) + "'"
                + " && documentVersion == '" + escape(String.valueOf(documentVersion)) + "'";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("'", "\\'");
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(value.toString());
    }

    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
