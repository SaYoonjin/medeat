package com.medeat.chatbot.service;

import com.medeat.medical.dto.DrugInfoSection;

import java.util.Set;

public record ChatbotRagQueryPlan(
        ChatbotQuestionScope scope,
        Set<DrugInfoSection> sections,
        String drugKeyword
) {

    public ChatbotRagQueryPlan {
        scope = scope == null ? ChatbotQuestionScope.PERSONAL_MEDICATION : scope;
        sections = sections == null ? Set.of() : Set.copyOf(sections);
        drugKeyword = drugKeyword == null ? "" : drugKeyword.strip();
    }

    public ChatbotRagQueryPlan(Set<DrugInfoSection> sections) {
        this(ChatbotQuestionScope.PERSONAL_MEDICATION, sections, "");
    }

    public boolean needsDrugEvidence() {
        return !sections.isEmpty();
    }

    public boolean isPersonalMedicationQuestion() {
        return scope == ChatbotQuestionScope.PERSONAL_MEDICATION;
    }

    public boolean isGeneralDrugInfoQuestion() {
        return scope == ChatbotQuestionScope.GENERAL_DRUG_INFO;
    }
}
