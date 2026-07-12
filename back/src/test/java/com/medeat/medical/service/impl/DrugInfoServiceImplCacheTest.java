package com.medeat.medical.service.impl;

import com.medeat.medical.dao.DrugInfoDao;
import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.util.PillNameCsvLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrugInfoServiceImplCacheTest {

    private final DrugInfoDao drugInfoDao = mock(DrugInfoDao.class);
    private final PillNameCsvLoader pillNameCsvLoader = mock(PillNameCsvLoader.class);
    private DrugInfoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new DrugInfoServiceImpl(drugInfoDao, pillNameCsvLoader));
        ReflectionTestUtils.setField(service, "cacheTtlDays", 30L);
    }

    @Test
    void returnsFreshDatabaseDataWithoutExternalRefresh() throws Exception {
        DrugInfoDto cached = drug(100L, LocalDateTime.now(), "구역이 나타날 수 있습니다.");
        when(drugInfoDao.selectByItemSeq(100L)).thenReturn(cached);

        DrugInfoDto result = service.getDrugInfoCached(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        assertThat(result).isSameAs(cached);
        verify(service, never()).fetchFreshDrugInfo(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );
        verify(drugInfoDao, never()).upsertDrugInfo(cached);
    }

    @Test
    void refreshesWhenRequiredSectionIsMissing() throws Exception {
        DrugInfoDto cached = drug(100L, LocalDateTime.now(), null);
        DrugInfoDto refreshed = drug(100L, LocalDateTime.now(), "새 부작용 정보");
        when(drugInfoDao.selectByItemSeq(100L)).thenReturn(cached);
        doReturn(refreshed).when(service).fetchFreshDrugInfo(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        DrugInfoDto result = service.getDrugInfoCached(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        assertThat(result.getSeQesitm()).isEqualTo("새 부작용 정보");
        verify(service).fetchFreshDrugInfo(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );
        verify(drugInfoDao).upsertDrugInfo(result);
    }

    @Test
    void refreshesWhenDatabaseDataIsStale() throws Exception {
        DrugInfoDto stale = drug(100L, LocalDateTime.now().minusDays(31), "오래된 정보");
        DrugInfoDto refreshed = drug(100L, LocalDateTime.now(), "최신 정보");
        when(drugInfoDao.selectByItemSeq(100L)).thenReturn(stale);
        doReturn(refreshed).when(service).fetchFreshDrugInfo(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        DrugInfoDto result = service.getDrugInfoCached(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );

        assertThat(result.getSeQesitm()).isEqualTo("최신 정보");
        verify(service).fetchFreshDrugInfo(
                100L,
                "테스트정",
                Set.of(DrugInfoSection.SIDE_EFFECT)
        );
    }

    private DrugInfoDto drug(Long itemSeq, LocalDateTime updatedAt, String sideEffect) {
        DrugInfoDto drug = new DrugInfoDto();
        drug.setItemSeq(itemSeq);
        drug.setItemName("테스트정");
        drug.setUpdatedAt(updatedAt);
        drug.setSeQesitm(sideEffect);
        return drug;
    }
}
