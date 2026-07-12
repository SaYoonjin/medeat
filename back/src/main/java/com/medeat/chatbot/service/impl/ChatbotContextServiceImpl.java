package com.medeat.chatbot.service.impl;

import com.medeat.chatbot.dto.ChatSource;
import com.medeat.chatbot.service.ChatbotContextService;
import com.medeat.chatbot.service.ChatbotGroundingContext;
import com.medeat.chatbot.service.ChatbotRagQueryPlan;
import com.medeat.chatbot.service.ChatbotRagQueryPlanner;
import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import com.medeat.medical.service.DrugInfoService;
import com.medeat.medical.service.MedicationService;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChatbotContextServiceImpl implements ChatbotContextService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotContextServiceImpl.class);
    private static final int MAX_MEDICATIONS = 20;
    private static final int MAX_FIELD_LENGTH = 2000;
    private static final String MFDS_PROVIDER = "식품의약품안전처 의약품안전나라";
    private static final String MFDS_DETAIL_URL =
            "https://nedrug.mfds.go.kr/pbp/CCBBB01/getItemDetail?itemSeq=";

    private final MedicationService medicationService;
    private final DrugInfoService drugInfoService;
    private final ChatbotRagQueryPlanner queryPlanner;
    private final Clock clock;

    @Autowired
    public ChatbotContextServiceImpl(
            MedicationService medicationService,
            DrugInfoService drugInfoService,
            ChatbotRagQueryPlanner queryPlanner
    ) {
        this(medicationService, drugInfoService, queryPlanner, Clock.systemUTC());
    }

    ChatbotContextServiceImpl(
            MedicationService medicationService,
            DrugInfoService drugInfoService,
            Clock clock
    ) {
        this(medicationService, drugInfoService, new ChatbotRagQueryPlanner(), clock);
    }

    ChatbotContextServiceImpl(
            MedicationService medicationService,
            DrugInfoService drugInfoService,
            ChatbotRagQueryPlanner queryPlanner,
            Clock clock
    ) {
        this.medicationService = medicationService;
        this.drugInfoService = drugInfoService;
        this.queryPlanner = queryPlanner;
        this.clock = clock;
    }

    @Override
    public ChatbotGroundingContext build(Long userId, String question) {
        Instant retrievedAt = clock.instant();
        List<MedicationDto> medications = medicationService.getMedicationList(userId);
        List<MedicationLogDto> todayLogs = medicationService.getTodayLogs(userId);
        Map<Long, Integer> takenCounts = countTodayLogs(todayLogs);
        List<ChatSource> sources = new ArrayList<>();

        StringBuilder context = new StringBuilder();
        context.append("[사용자 복약 데이터]\n");
        context.append("- 기준 시각(UTC): ").append(retrievedAt).append('\n');

        ChatbotRagQueryPlan queryPlan = queryPlanner.plan(question, medications);
        context.append("- 질문 범위: ")
                .append(queryPlan.isPersonalMedicationQuestion()
                        ? "개인 복약 질문"
                        : "일반 의약품 질문")
                .append('\n');
        context.append("- RAG 검색 섹션: ")
                .append(queryPlan.needsDrugEvidence()
                        ? queryPlan.sections().stream().map(DrugInfoSection::getLabel).toList()
                        : "없음(복약 현황만 사용)")
                .append('\n');

        if (queryPlan.isGeneralDrugInfoQuestion()) {
            appendGeneralDrugEvidence(
                    context,
                    queryPlan,
                    sources,
                    retrievedAt
            );
            return new ChatbotGroundingContext(context.toString(), sources, retrievedAt);
        }

        if (medications == null || medications.isEmpty()) {
            context.append("- 등록된 약이 없습니다.\n");
            return new ChatbotGroundingContext(context.toString(), sources, retrievedAt);
        }

        List<MedicationDto> selectedMedications = queryPlanner.selectMedications(question, medications);

        int medicationCount = Math.min(selectedMedications.size(), MAX_MEDICATIONS);
        for (int index = 0; index < medicationCount; index++) {
            MedicationDto medication = selectedMedications.get(index);
            appendMedication(context, medication, takenCounts);
            if (queryPlan.needsDrugEvidence()) {
                appendDrugEvidence(
                        context,
                        medication,
                        queryPlan.sections(),
                        sources,
                        retrievedAt
                );
            }
        }

        if (selectedMedications.size() > MAX_MEDICATIONS) {
            context.append("- 나머지 ")
                    .append(selectedMedications.size() - MAX_MEDICATIONS)
                    .append("개 약은 이번 답변의 근거에서 제외되었습니다.\n");
        }

        return new ChatbotGroundingContext(context.toString(), sources, retrievedAt);
    }

    private void appendGeneralDrugEvidence(
            StringBuilder context,
            ChatbotRagQueryPlan queryPlan,
            List<ChatSource> sources,
            Instant retrievedAt
    ) {
        context.append("\n[일반 의약품 검색]\n");
        if (!queryPlan.needsDrugEvidence()) {
            context.append("- 의약품 문서 근거가 필요한 질문으로 분류되지 않았습니다.\n");
            return;
        }

        String keyword = queryPlan.drugKeyword();
        if (keyword == null || keyword.isBlank()) {
            context.append("- 약품명을 식별하지 못했습니다. 제품명을 다시 확인해야 합니다.\n");
            return;
        }
        context.append("- 검색 약품명 후보 키워드: ").append(clean(keyword)).append('\n');

        List<DrugInfoDto> candidates;
        try {
            candidates = drugInfoService.searchDrugHybrid(keyword);
        } catch (Exception exception) {
            log.warn("Failed to search general drug info. keyword={}", keyword, exception);
            context.append("- 식약처 근거: 약품명 검색 중 오류가 발생함\n");
            return;
        }

        List<DrugInfoDto> validCandidates = distinctCandidates(candidates);
        if (validCandidates.isEmpty()) {
            context.append("- 식약처 근거: 확인 가능한 약품 후보가 없음\n");
            return;
        }
        if (validCandidates.size() > 1) {
            context.append("- 식약처 근거: 약품 후보가 여러 개입니다. 임의로 하나를 선택하지 마세요.\n");
            int count = Math.min(validCandidates.size(), 5);
            for (int index = 0; index < count; index++) {
                DrugInfoDto candidate = validCandidates.get(index);
                context.append("  - 후보 ")
                        .append(index + 1)
                        .append(": ")
                        .append(clean(candidate.getItemName()))
                        .append(" / itemSeq: ")
                        .append(candidate.getItemSeq())
                        .append(" / 제조사: ")
                        .append(clean(candidate.getEntpName()))
                        .append('\n');
            }
            context.append("- 제품명, 제조사, 제형을 확인해 다시 질문하도록 안내하세요.\n");
            return;
        }

        DrugInfoDto candidate = validCandidates.get(0);
        try {
            appendDrugEvidence(
                    context,
                    candidate.getItemSeq(),
                    candidate.getItemName(),
                    queryPlan.sections(),
                    sources,
                    retrievedAt
            );
        } catch (Exception exception) {
            log.warn("Failed to load general drug evidence. itemSeq={}", candidate.getItemSeq(), exception);
            context.append("  - 식약처 근거: 조회 중 오류가 발생함\n");
        }
    }

    private List<DrugInfoDto> distinctCandidates(List<DrugInfoDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, DrugInfoDto> byItemSeq = new HashMap<>();
        for (DrugInfoDto candidate : candidates) {
            if (candidate == null || candidate.getItemSeq() == null) {
                continue;
            }
            byItemSeq.putIfAbsent(candidate.getItemSeq(), candidate);
        }
        return new ArrayList<>(byItemSeq.values());
    }

    private Map<Long, Integer> countTodayLogs(List<MedicationLogDto> logs) {
        Map<Long, Integer> counts = new HashMap<>();
        if (logs == null) {
            return counts;
        }
        for (MedicationLogDto logEntry : logs) {
            if (logEntry != null && logEntry.getMedicationId() != null) {
                counts.merge(logEntry.getMedicationId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void appendMedication(
            StringBuilder context,
            MedicationDto medication,
            Map<Long, Integer> takenCounts
    ) {
        int takenCount = takenCounts.getOrDefault(medication.getMedicationId(), 0);
        String expectedCount = medication.getDailyCount() > 0
                ? Integer.toString(medication.getDailyCount())
                : "미등록";

        context.append("\n- 등록 약: ").append(clean(medication.getDrugName())).append('\n');
        context.append("  - medicationId: ").append(medication.getMedicationId()).append('\n');
        context.append("  - 품목기준코드(itemSeq): ")
                .append(medication.getItemSeq() == null ? "미등록" : medication.getItemSeq())
                .append('\n');
        context.append("  - 복용 시간: ").append(clean(medication.getIntakeTime())).append('\n');
        context.append("  - 용량: ").append(clean(medication.getDose())).append('\n');
        context.append("  - 오늘 복용 기록: ").append(takenCount)
                .append("회 / 예정 ").append(expectedCount).append("회\n");
    }

    private void appendDrugEvidence(
            StringBuilder context,
            MedicationDto medication,
            Set<DrugInfoSection> sections,
            List<ChatSource> sources,
            Instant retrievedAt
    ) {
        if (medication.getItemSeq() == null) {
            context.append("  - 식약처 근거: 품목기준코드가 없어 조회하지 못함\n");
            return;
        }

        try {
            appendDrugEvidence(
                    context,
                    medication.getItemSeq(),
                    medication.getDrugName(),
                    sections,
                    sources,
                    retrievedAt
            );
        } catch (Exception exception) {
            log.warn("Failed to load chatbot drug evidence. itemSeq={}", medication.getItemSeq(), exception);
            context.append("  - 식약처 근거: 조회 중 오류가 발생함\n");
        }
    }

    private void appendDrugEvidence(
            StringBuilder context,
            Long itemSeq,
            String nameHint,
            Set<DrugInfoSection> sections,
            List<ChatSource> sources,
            Instant retrievedAt
    ) throws Exception {
        DrugInfoDto drug = drugInfoService.getDrugInfoCached(
                itemSeq,
                nameHint,
                sections
        );
        if (drug == null || !hasEvidence(drug, sections)) {
            context.append("  - 식약처 근거: 조회 결과 없음\n");
            return;
        }

        context.append("  - 식약처 제품명: ").append(clean(drug.getItemName())).append('\n');
        for (DrugInfoSection section : sections) {
            appendField(context, section.getLabel(), section.getValue(drug));
        }

        sources.add(new ChatSource(
                MFDS_PROVIDER,
                valueOrDefault(drug.getItemName(), nameHint),
                itemSeq,
                MFDS_DETAIL_URL + itemSeq,
                drug.getUpdatedAt() == null
                        ? retrievedAt
                        : drug.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant()
        ));
    }

    private void appendField(StringBuilder context, String label, String value) {
        if (value != null && !value.isBlank()) {
            context.append("  - ").append(label).append(": ").append(clean(value)).append('\n');
        }
    }

    private boolean hasEvidence(DrugInfoDto drug, Set<DrugInfoSection> sections) {
        return sections.stream().anyMatch(section -> hasText(section.getValue(drug)));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return "미등록";
        }
        String text = Jsoup.parse(value).text().replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_FIELD_LENGTH
                ? text
                : text.substring(0, MAX_FIELD_LENGTH) + "...";
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
