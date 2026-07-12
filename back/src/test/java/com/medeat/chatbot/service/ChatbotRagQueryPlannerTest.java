package com.medeat.chatbot.service;

import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.medical.dto.MedicationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatbotRagQueryPlannerTest {

    private final ChatbotRagQueryPlanner planner = new ChatbotRagQueryPlanner();

    @Test
    void sideEffectQuestionRetrievesOnlyRelevantSections() {
        ChatbotRagQueryPlan plan = planner.plan(
                "타이레놀을 먹고 속이 메스꺼워",
                List.of(medication("타이레놀정500밀리그램"))
        );

        assertThat(plan.sections())
                .containsExactlyInAnyOrder(
                        DrugInfoSection.SIDE_EFFECT,
                        DrugInfoSection.PRECAUTION
                );
    }

    @Test
    void medicationScheduleQuestionDoesNotRetrieveDrugDocuments() {
        ChatbotRagQueryPlan plan = planner.plan(
                "오늘 저녁에 먹을 약이 뭐야?",
                List.of(medication("타이레놀정500밀리그램"))
        );

        assertThat(plan.needsDrugEvidence()).isFalse();
        assertThat(plan.isPersonalMedicationQuestion()).isTrue();
    }

    @Test
    void selectsMentionedMedicationInsteadOfEveryRegisteredMedication() {
        MedicationDto tylenol = medication("타이레놀정500밀리그램");
        MedicationDto vitamin = medication("비타민정");

        List<MedicationDto> selected = planner.selectMedications(
                "타이레놀 부작용 알려줘",
                List.of(tylenol, vitamin)
        );

        assertThat(selected).containsExactly(tylenol);
    }

    @Test
    void classifiesUnregisteredDrugQuestionAsGeneralDrugInfo() {
        ChatbotRagQueryPlan plan = planner.plan(
                "타이레놀 부작용 알려줘",
                List.of(medication("비타민정"))
        );

        assertThat(plan.isGeneralDrugInfoQuestion()).isTrue();
        assertThat(plan.sections())
                .containsExactlyInAnyOrder(
                        DrugInfoSection.SIDE_EFFECT,
                        DrugInfoSection.PRECAUTION
                );
        assertThat(plan.drugKeyword()).isEqualTo("타이레놀");
    }

    @Test
    void classifiesRegisteredDrugQuestionAsPersonalMedication() {
        ChatbotRagQueryPlan plan = planner.plan(
                "타이레놀 부작용 알려줘",
                List.of(medication("타이레놀정500밀리그램"))
        );

        assertThat(plan.isPersonalMedicationQuestion()).isTrue();
    }

    private MedicationDto medication(String name) {
        MedicationDto medication = new MedicationDto();
        medication.setDrugName(name);
        return medication;
    }
}
