package com.medeat.chatbot.service.impl;

import com.medeat.chatbot.service.ChatbotGroundingContext;
import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import com.medeat.medical.service.DrugInfoService;
import com.medeat.medical.service.MedicationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ChatbotContextServiceImplTest {

    private final MedicationService medicationService = mock(MedicationService.class);
    private final DrugInfoService drugInfoService = mock(DrugInfoService.class);
    private final Instant now = Instant.parse("2026-07-02T03:00:00Z");
    private final ChatbotContextServiceImpl contextService = new ChatbotContextServiceImpl(
            medicationService,
            drugInfoService,
            Clock.fixed(now, ZoneOffset.UTC)
    );

    @Test
    void buildIncludesMedicationStatusAndMfdsEvidence() throws Exception {
        MedicationDto medication = medication(10L, 200808876L, "테스트정", "저녁", 2);
        MedicationLogDto log = new MedicationLogDto();
        log.setMedicationId(10L);

        DrugInfoDto drug = new DrugInfoDto();
        drug.setItemSeq(200808876L);
        drug.setItemName("테스트정");
        drug.setEfcyQesitm("<p>통증을 완화합니다.</p>");
        drug.setUseMethodQesitm("정해진 용법에 따라 복용합니다.");
        drug.setIntrcQesitm("다른 약과 함께 복용하기 전에 전문가와 상의합니다.");
        drug.setSeQesitm("<p>구역이나 구토가 나타날 수 있습니다.</p>");
        drug.setAtpnQesitm("이상 증상이 있으면 전문가와 상의합니다.");

        when(medicationService.getMedicationList(1L)).thenReturn(List.of(medication));
        when(medicationService.getTodayLogs(1L)).thenReturn(List.of(log));
        when(drugInfoService.getDrugInfoCached(
                200808876L,
                "테스트정",
                java.util.Set.of(DrugInfoSection.SIDE_EFFECT, DrugInfoSection.PRECAUTION)
        )).thenReturn(drug);

        ChatbotGroundingContext result = contextService.build(1L, "테스트정 부작용이 궁금해");

        assertThat(result.content())
                .contains("등록 약: 테스트정")
                .contains("복용 시간: 저녁")
                .contains("오늘 복용 기록: 1회 / 예정 2회")
                .contains("RAG 검색 섹션")
                .contains("부작용")
                .contains("구역이나 구토가 나타날 수 있습니다.")
                .doesNotContain("<p>");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).getProvider()).isEqualTo("식품의약품안전처 의약품안전나라");
        assertThat(result.sources().get(0).getItemSeq()).isEqualTo(200808876L);
        assertThat(result.sources().get(0).getRetrievedAt()).isEqualTo(now);
    }

    @Test
    void buildDoesNotInventEvidenceWhenItemSeqIsMissing() {
        MedicationDto medication = medication(10L, null, "코드없는약", "아침", 1);
        when(medicationService.getMedicationList(1L)).thenReturn(List.of(medication));
        when(medicationService.getTodayLogs(1L)).thenReturn(List.of());

        ChatbotGroundingContext result = contextService.build(1L, "코드없는약 주의사항 알려줘");

        assertThat(result.content())
                .contains("품목기준코드(itemSeq): 미등록")
                .contains("식약처 근거: 품목기준코드가 없어 조회하지 못함");
        assertThat(result.sources()).isEmpty();
    }

    @Test
    void buildSearchesGeneralDrugInfoEvenWhenDrugIsNotRegistered() throws Exception {
        DrugInfoDto candidate = drug(200808876L, "타이레놀정500밀리그램");
        DrugInfoDto detail = drug(200808876L, "타이레놀정500밀리그램");
        detail.setSeQesitm("<p>구역이나 구토가 나타날 수 있습니다.</p>");
        detail.setAtpnQesitm("이상 증상이 있으면 전문가와 상의합니다.");

        when(medicationService.getMedicationList(1L)).thenReturn(List.of(medication(10L, 1L, "비타민정", "아침", 1)));
        when(medicationService.getTodayLogs(1L)).thenReturn(List.of());
        when(drugInfoService.searchDrugHybrid("타이레놀")).thenReturn(List.of(candidate));
        when(drugInfoService.getDrugInfoCached(
                200808876L,
                "타이레놀정500밀리그램",
                java.util.Set.of(DrugInfoSection.SIDE_EFFECT, DrugInfoSection.PRECAUTION)
        )).thenReturn(detail);

        ChatbotGroundingContext result = contextService.build(1L, "타이레놀 부작용 알려줘");

        assertThat(result.content())
                .contains("질문 범위: 일반 의약품 질문")
                .contains("[일반 의약품 검색]")
                .contains("검색 약품명 후보 키워드: 타이레놀")
                .contains("식약처 제품명: 타이레놀정500밀리그램")
                .contains("구역이나 구토가 나타날 수 있습니다.");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).getItemSeq()).isEqualTo(200808876L);
    }

    @Test
    void buildDoesNotPickOneWhenGeneralDrugSearchHasMultipleCandidates() throws Exception {
        when(medicationService.getMedicationList(1L)).thenReturn(List.of());
        when(medicationService.getTodayLogs(1L)).thenReturn(List.of());
        when(drugInfoService.searchDrugHybrid("타이레놀")).thenReturn(List.of(
                drug(1L, "타이레놀정500밀리그램"),
                drug(2L, "어린이타이레놀현탁액")
        ));

        ChatbotGroundingContext result = contextService.build(1L, "타이레놀 부작용 알려줘");

        assertThat(result.content())
                .contains("질문 범위: 일반 의약품 질문")
                .contains("약품 후보가 여러 개입니다")
                .contains("타이레놀정500밀리그램")
                .contains("어린이타이레놀현탁액")
                .contains("제품명, 제조사, 제형을 확인해 다시 질문");
        assertThat(result.sources()).isEmpty();
        verify(drugInfoService, never()).getDrugInfoCached(anyLong(), anyString(), anySet());
    }

    private MedicationDto medication(
            Long medicationId,
            Long itemSeq,
            String name,
            String intakeTime,
            int dailyCount
    ) {
        MedicationDto medication = new MedicationDto();
        medication.setMedicationId(medicationId);
        medication.setItemSeq(itemSeq);
        medication.setDrugName(name);
        medication.setIntakeTime(intakeTime);
        medication.setDose("1정");
        medication.setDailyCount(dailyCount);
        return medication;
    }

    private DrugInfoDto drug(Long itemSeq, String itemName) {
        DrugInfoDto drug = new DrugInfoDto();
        drug.setItemSeq(itemSeq);
        drug.setItemName(itemName);
        drug.setEntpName("테스트제약");
        return drug;
    }
}
