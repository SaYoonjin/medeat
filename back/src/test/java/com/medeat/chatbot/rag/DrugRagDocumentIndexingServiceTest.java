package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrugRagDocumentIndexingServiceTest {

    private final RagDocumentDao ragDocumentDao = mock(RagDocumentDao.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-12T01:00:00Z"), ZoneOffset.UTC);
    private final DrugRagDocumentIndexingService service = new DrugRagDocumentIndexingService(
            ragDocumentDao,
            20,
            5,
            clock
    );

    @Test
    void prepareIndexingSkipsSectionWhenActiveHashIsSame() {
        DrugInfoDto drug = drug();
        drug.setSeQesitm("<p>구역이나 구토가 나타날 수 있습니다.</p>");
        String normalized = "구역이나 구토가 나타날 수 있습니다.";
        String hash = service.sha256(normalized);

        when(ragDocumentDao.findActiveDocument(200808876L, DrugInfoSection.SIDE_EFFECT))
                .thenReturn(Optional.of(new RagDocument(
                        1L,
                        200808876L,
                        "테스트정",
                        DrugInfoSection.SIDE_EFFECT,
                        normalized,
                        hash,
                        1,
                        DrugRagDocumentIndexingService.MFDS_SOURCE,
                        null,
                        RagDocumentLifecycleStatus.ACTIVE
                )));

        RagDocumentIndexingResult result = service.prepareIndexing(drug);

        assertThat(result.scannedSections()).isEqualTo(1);
        assertThat(result.skippedSections()).isEqualTo(1);
        assertThat(result.createdDocuments()).isZero();
        verify(ragDocumentDao, never()).insertDocument(
                anyLong(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyInt(),
                anyString(),
                any(),
                any()
        );
    }

    @Test
    void prepareIndexingCreatesDocumentChunksAndJobsForChangedSection() {
        DrugInfoDto drug = drug();
        drug.setSeQesitm("123456789012345678901234567890");

        when(ragDocumentDao.findActiveDocument(200808876L, DrugInfoSection.SIDE_EFFECT))
                .thenReturn(Optional.empty());
        when(ragDocumentDao.findMaxDocumentVersion(200808876L, DrugInfoSection.SIDE_EFFECT))
                .thenReturn(2);
        when(ragDocumentDao.insertDocument(
                eq(200808876L),
                eq("테스트정"),
                eq(DrugInfoSection.SIDE_EFFECT),
                anyString(),
                anyString(),
                eq(3),
                eq(DrugRagDocumentIndexingService.MFDS_SOURCE),
                any(),
                eq(RagDocumentLifecycleStatus.INDEXING)
        )).thenReturn(10L);
        when(ragDocumentDao.insertChunk(eq(10L), anyInt(), anyString(), anyString()))
                .thenReturn(101L, 102L);
        when(ragDocumentDao.insertPendingIndexJob(anyLong())).thenReturn(1);

        RagDocumentIndexingResult result = service.prepareIndexing(drug);

        assertThat(result.scannedSections()).isEqualTo(1);
        assertThat(result.skippedSections()).isZero();
        assertThat(result.createdDocuments()).isEqualTo(1);
        assertThat(result.createdChunks()).isEqualTo(2);
        assertThat(result.createdJobs()).isEqualTo(2);

        verify(ragDocumentDao, times(2)).insertChunk(eq(10L), anyInt(), anyString(), anyString());
        verify(ragDocumentDao).insertPendingIndexJob(101L);
        verify(ragDocumentDao).insertPendingIndexJob(102L);
    }

    @Test
    void splitIntoChunksNormalizesHtmlAndKeepsOverlap() {
        List<RagChunk> chunks = service.splitIntoChunks("<p>1234567890</p><p>abcdefghij</p><p>XYZ</p>");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("1234567890 abcdefghi");
        assertThat(chunks.get(1).content()).isEqualTo("efghij XYZ");
        assertThat(chunks.get(0).contentHash()).hasSize(64);
        assertThat(chunks.get(1).contentHash()).hasSize(64);
    }

    @Test
    void prepareIndexingStoresNormalizedHtmlContent() {
        DrugInfoDto drug = drug();
        drug.setAtpnQesitm("<div>  전문가와   상의하세요. </div>");

        when(ragDocumentDao.findActiveDocument(200808876L, DrugInfoSection.PRECAUTION))
                .thenReturn(Optional.empty());
        when(ragDocumentDao.findMaxDocumentVersion(200808876L, DrugInfoSection.PRECAUTION))
                .thenReturn(0);
        when(ragDocumentDao.insertDocument(
                anyLong(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyInt(),
                anyString(),
                any(),
                any()
        )).thenReturn(10L);
        when(ragDocumentDao.insertChunk(anyLong(), anyInt(), anyString(), anyString())).thenReturn(100L);
        when(ragDocumentDao.insertPendingIndexJob(anyLong())).thenReturn(1);

        service.prepareIndexing(drug);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragDocumentDao).insertDocument(
                anyLong(),
                anyString(),
                eq(DrugInfoSection.PRECAUTION),
                contentCaptor.capture(),
                anyString(),
                anyInt(),
                anyString(),
                any(),
                any()
        );
        assertThat(contentCaptor.getValue()).isEqualTo("전문가와 상의하세요.");
    }

    private DrugInfoDto drug() {
        DrugInfoDto drug = new DrugInfoDto();
        drug.setItemSeq(200808876L);
        drug.setItemName("테스트정");
        return drug;
    }
}
