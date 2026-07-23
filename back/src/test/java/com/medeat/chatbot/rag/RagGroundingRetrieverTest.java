package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagGroundingRetrieverTest {

    private final DrugRagDocumentIndexingService indexingService =
            mock(DrugRagDocumentIndexingService.class);
    private final RagDocumentDao ragDocumentDao = mock(RagDocumentDao.class);
    private final RagVectorStoreService vectorStoreService = mock(RagVectorStoreService.class);

    @Test
    void retrievesQuestionRelevantChunksFromActiveDocumentVersion() {
        RagGroundingRetriever retriever = new RagGroundingRetriever(
                indexingService,
                ragDocumentDao,
                provider(vectorStoreService),
                50
        );
        DrugInfoDto drug = drug();
        RagDocument active = new RagDocument(
                10L,
                200808876L,
                "테스트정",
                DrugInfoSection.SIDE_EFFECT,
                "전체 부작용 문서",
                "hash",
                3,
                "MFDS_NEDRUG",
                null,
                RagDocumentLifecycleStatus.ACTIVE
        );
        RagVectorSearchResult result = new RagVectorSearchResult(
                "vector-1",
                "구역이나 구토가 나타날 수 있습니다.",
                200808876L,
                "테스트정",
                DrugInfoSection.SIDE_EFFECT,
                3,
                10L,
                100L
        );

        when(ragDocumentDao.findActiveDocument(200808876L, DrugInfoSection.SIDE_EFFECT))
                .thenReturn(Optional.of(active));
        when(vectorStoreService.searchSimilarChunks(
                "약을 먹고 속이 울렁거려",
                200808876L,
                DrugInfoSection.SIDE_EFFECT,
                3
        )).thenReturn(List.of(result));

        Map<DrugInfoSection, List<RagVectorSearchResult>> evidence = retriever.retrieve(
                "약을 먹고 속이 울렁거려",
                drug,
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        assertThat(evidence.get(DrugInfoSection.SIDE_EFFECT)).containsExactly(result);
        verify(indexingService).prepareIndexing(drug);
        verify(vectorStoreService).savePendingChunks(50);
    }

    @Test
    void returnsEmptyEvidenceWhenVectorStoreIsUnavailable() {
        RagGroundingRetriever retriever = new RagGroundingRetriever(
                indexingService,
                ragDocumentDao,
                provider(null),
                50
        );

        assertThat(retriever.retrieve(
                "부작용 알려줘",
                drug(),
                Set.of(DrugInfoSection.SIDE_EFFECT)
        )).isEmpty();
    }

    private DrugInfoDto drug() {
        DrugInfoDto drug = new DrugInfoDto();
        drug.setItemSeq(200808876L);
        drug.setItemName("테스트정");
        drug.setSeQesitm("구역이나 구토가 나타날 수 있습니다.");
        return drug;
    }

    private ObjectProvider<RagVectorStoreService> provider(RagVectorStoreService service) {
        return new ObjectProvider<>() {
            @Override
            public RagVectorStoreService getObject(Object... args) {
                return service;
            }

            @Override
            public RagVectorStoreService getIfAvailable() {
                return service;
            }

            @Override
            public RagVectorStoreService getIfUnique() {
                return service;
            }

            @Override
            public RagVectorStoreService getObject() {
                return service;
            }

            @Override
            public java.util.Iterator<RagVectorStoreService> iterator() {
                return service == null
                        ? java.util.Collections.emptyIterator()
                        : List.of(service).iterator();
            }

            @Override
            public Stream<RagVectorStoreService> stream() {
                return service == null ? Stream.empty() : Stream.of(service);
            }

            @Override
            public Stream<RagVectorStoreService> orderedStream() {
                return stream();
            }
        };
    }
}
