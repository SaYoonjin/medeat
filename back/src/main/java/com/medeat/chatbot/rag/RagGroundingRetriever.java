package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RagGroundingRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagGroundingRetriever.class);

    private final DrugRagDocumentIndexingService indexingService;
    private final RagDocumentDao ragDocumentDao;
    private final ObjectProvider<RagVectorStoreService> vectorStoreServiceProvider;
    private final int indexingBatchSize;

    public RagGroundingRetriever(
            DrugRagDocumentIndexingService indexingService,
            RagDocumentDao ragDocumentDao,
            ObjectProvider<RagVectorStoreService> vectorStoreServiceProvider,
            @Value("${medeat.rag.indexing.batch-size:50}") int indexingBatchSize
    ) {
        this.indexingService = indexingService;
        this.ragDocumentDao = ragDocumentDao;
        this.vectorStoreServiceProvider = vectorStoreServiceProvider;
        this.indexingBatchSize = indexingBatchSize;
    }

    public Map<DrugInfoSection, List<RagVectorSearchResult>> retrieve(
            String question,
            DrugInfoDto drug,
            Set<DrugInfoSection> sections
    ) {
        if (question == null || question.isBlank()
                || drug == null || drug.getItemSeq() == null
                || sections == null || sections.isEmpty()) {
            return Map.of();
        }

        RagVectorStoreService vectorStoreService = vectorStoreServiceProvider.getIfAvailable();
        if (vectorStoreService == null) {
            return Map.of();
        }

        try {
            indexingService.prepareIndexing(drug);
            vectorStoreService.savePendingChunks(indexingBatchSize);

            Map<DrugInfoSection, List<RagVectorSearchResult>> evidence =
                    new EnumMap<>(DrugInfoSection.class);
            for (DrugInfoSection section : sections) {
                ragDocumentDao.findActiveDocument(drug.getItemSeq(), section)
                        .map(active -> vectorStoreService.searchSimilarChunks(
                                question,
                                drug.getItemSeq(),
                                section,
                                active.documentVersion()
                        ))
                        .filter(results -> !results.isEmpty())
                        .ifPresent(results -> evidence.put(section, results));
            }
            return Map.copyOf(evidence);
        } catch (RuntimeException exception) {
            log.warn(
                    "Vector RAG retrieval failed; falling back to relational drug evidence. itemSeq={}",
                    drug.getItemSeq(),
                    exception
            );
            return Map.of();
        }
    }
}
