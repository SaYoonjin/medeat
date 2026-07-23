package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagVectorStoreServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RagDocumentDao ragDocumentDao = mock(RagDocumentDao.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-12T02:00:00Z"), ZoneOffset.UTC);
    private final RagVectorStoreService service = new RagVectorStoreService(
            vectorStore,
            ragDocumentDao,
            5,
            clock
    );

    @Test
    void savePendingChunksStoresDocumentsThroughVectorStoreAndMarksCompleted() {
        RagVectorChunk chunk = chunk(100L, 10L, 0, "구역이나 구토가 나타날 수 있습니다.");
        when(ragDocumentDao.findPendingVectorChunks(50)).thenReturn(List.of(chunk));

        int savedCount = service.savePendingChunks(50);

        assertThat(savedCount).isEqualTo(1);

        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());

        Document document = documentsCaptor.getValue().get(0);
        assertThat(document.getText()).isEqualTo("구역이나 구토가 나타날 수 있습니다.");
        assertThat(document.getMetadata())
                .containsEntry("itemSeq", "200808876")
                .containsEntry("sectionType", "SIDE_EFFECT")
                .containsEntry("documentVersion", "3")
                .containsEntry("ragDocumentId", "10")
                .containsEntry("ragChunkId", "100")
                .containsEntry("chunkIndex", 0);

        verify(ragDocumentDao).updateChunkVectorId(100L, document.getId());
        verify(ragDocumentDao).markIndexJobCompleted(
                100L,
                LocalDateTime.ofInstant(clock.instant(), clock.getZone())
        );
        verify(ragDocumentDao).activateDocumentIfReady(10L);
    }

    @Test
    void searchSimilarChunksUsesMetadataFilter() {
        Document result = new Document("검색된 부작용 근거", Map.of(
                "itemSeq", "200808876",
                "drugName", "타이레놀정500밀리그램",
                "sectionType", "SIDE_EFFECT",
                "documentVersion", "3",
                "ragDocumentId", "10",
                "ragChunkId", "100"
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(result));

        List<RagVectorSearchResult> results = service.searchSimilarChunks(
                "속이 울렁거려",
                200808876L,
                DrugInfoSection.SIDE_EFFECT,
                3,
                4
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("검색된 부작용 근거");
        assertThat(results.get(0).itemSeq()).isEqualTo(200808876L);
        assertThat(results.get(0).sectionType()).isEqualTo(DrugInfoSection.SIDE_EFFECT);
        assertThat(results.get(0).documentVersion()).isEqualTo(3);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("속이 울렁거려");
        assertThat(request.getTopK()).isEqualTo(4);
        assertThat(request.getFilterExpression().toString())
                .contains("itemSeq")
                .contains("200808876")
                .contains("sectionType")
                .contains("SIDE_EFFECT")
                .contains("documentVersion")
                .contains("3");
    }

    @Test
    void deleteDocumentVersionDeletesByFilterExpression() {
        service.deleteDocumentVersion(200808876L, DrugInfoSection.SIDE_EFFECT, 2);

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStore).delete(filterCaptor.capture());
        assertThat(filterCaptor.getValue())
                .contains("itemSeq == '200808876'")
                .contains("sectionType == 'SIDE_EFFECT'")
                .contains("documentVersion == '2'");
    }

    private RagVectorChunk chunk(Long chunkId, Long documentId, int chunkIndex, String content) {
        return new RagVectorChunk(
                chunkId,
                documentId,
                chunkIndex,
                content,
                200808876L,
                "타이레놀정500밀리그램",
                DrugInfoSection.SIDE_EFFECT,
                3,
                DrugRagDocumentIndexingService.MFDS_SOURCE,
                LocalDateTime.of(2026, 7, 12, 11, 0)
        );
    }
}
