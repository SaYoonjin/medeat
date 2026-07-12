package com.medeat.chatbot.service;

import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.medical.dto.MedicationDto;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ChatbotRagQueryPlanner {

    private static final Pattern DRUG_FORM_SUFFIX = Pattern.compile(
            "(연질캡슐|서방정|캡슐|정|시럽|현탁액|과립|연고|크림|액|주).*$"
    );
    private static final Pattern QUESTION_NOISE = Pattern.compile(
            "(부작용|이상반응|주의사항|주의|조심|금기|경고|상호작용|병용|효능|효과|복용법|먹는법|용량|보관|냉장|유통기한|무슨\\s*약|어떤\\s*약|알려줘|궁금해|뭐야|있어|있나요|인가요|먹어도\\s*돼|복용해도\\s*돼|술|음주|같이|함께|이랑|랑|하고|\\?|\\.)"
    );

    public ChatbotRagQueryPlan plan(String question, List<MedicationDto> medications) {
        String query = normalize(question);
        EnumSet<DrugInfoSection> sections = EnumSet.noneOf(DrugInfoSection.class);

        addWhenMatched(sections, query, DrugInfoSection.EFFICACY,
                "효능", "효과", "무슨약", "어떤약", "어디에좋", "용도");
        addWhenMatched(sections, query, DrugInfoSection.USAGE,
                "복용법", "먹는법", "몇정", "몇알", "용량", "공복", "식전", "식후");
        if (containsAny(query, "주의", "조심", "금기", "임신", "알레르기", "경고")) {
            sections.add(DrugInfoSection.WARNING);
            sections.add(DrugInfoSection.PRECAUTION);
        }
        if (containsAny(query, "같이", "함께", "상호작용", "병용", "술", "음주", "음식")) {
            sections.add(DrugInfoSection.INTERACTION);
            sections.add(DrugInfoSection.PRECAUTION);
        }
        if (containsAny(query, "부작용", "이상반응", "메스꺼", "구토", "어지러", "발진", "속이안좋")) {
            sections.add(DrugInfoSection.SIDE_EFFECT);
            sections.add(DrugInfoSection.PRECAUTION);
        }
        addWhenMatched(sections, query, DrugInfoSection.STORAGE,
                "보관", "냉장", "유통기한");

        if (sections.isEmpty() && mentionsRegisteredDrug(query, medications)) {
            sections.add(DrugInfoSection.EFFICACY);
            sections.add(DrugInfoSection.USAGE);
            sections.add(DrugInfoSection.WARNING);
        }

        ChatbotQuestionScope scope = determineScope(query, sections, medications);
        String drugKeyword = scope == ChatbotQuestionScope.GENERAL_DRUG_INFO
                ? extractDrugKeyword(question)
                : "";

        return new ChatbotRagQueryPlan(scope, sections, drugKeyword);
    }

    public List<MedicationDto> selectMedications(String question, List<MedicationDto> medications) {
        if (medications == null || medications.isEmpty()) {
            return List.of();
        }

        String query = normalize(question);
        List<MedicationDto> matched = medications.stream()
                .filter(medication -> mentionsMedication(query, medication))
                .toList();
        return matched.isEmpty() ? medications : matched;
    }

    private boolean mentionsRegisteredDrug(String query, List<MedicationDto> medications) {
        return medications != null && medications.stream()
                .anyMatch(medication -> mentionsMedication(query, medication));
    }

    private boolean mentionsMedication(String query, MedicationDto medication) {
        if (medication == null || medication.getDrugName() == null) {
            return false;
        }
        String fullName = normalize(medication.getDrugName());
        String baseName = normalize(medication.getDrugName().replaceAll("\\(.*?\\)", ""));
        String shortName = baseName.replaceFirst(
                DRUG_FORM_SUFFIX.pattern(),
                ""
        );
        String prefix = shortName.substring(0, Math.min(shortName.length(), 4));
        return (!fullName.isBlank() && query.contains(fullName))
                || (!baseName.isBlank() && query.contains(baseName))
                || (prefix.length() >= 2 && query.contains(prefix));
    }

    private void addWhenMatched(
            Set<DrugInfoSection> sections,
            String query,
            DrugInfoSection section,
            String... keywords
    ) {
        if (containsAny(query, keywords)) {
            sections.add(section);
        }
    }

    private boolean containsAny(String query, String... keywords) {
        for (String keyword : keywords) {
            if (query.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private ChatbotQuestionScope determineScope(
            String query,
            Set<DrugInfoSection> sections,
            List<MedicationDto> medications
    ) {
        if (isPersonalQuestion(query)) {
            return ChatbotQuestionScope.PERSONAL_MEDICATION;
        }
        if (sections.isEmpty()) {
            return ChatbotQuestionScope.PERSONAL_MEDICATION;
        }
        if (mentionsRegisteredDrug(query, medications)) {
            return ChatbotQuestionScope.PERSONAL_MEDICATION;
        }
        return ChatbotQuestionScope.GENERAL_DRUG_INFO;
    }

    private boolean isPersonalQuestion(String query) {
        return containsAny(
                query,
                "내가먹는",
                "내약",
                "내가복용",
                "나의약",
                "등록한약",
                "복용중인약",
                "오늘먹을약",
                "오늘저녁",
                "오늘아침",
                "오늘점심",
                "먹었어",
                "복용했어"
        );
    }

    private String extractDrugKeyword(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String keyword = QUESTION_NOISE.matcher(question).replaceAll(" ");
        keyword = keyword.replaceAll("[,!?]", " ")
                .replaceAll("\\s+", " ")
                .strip();

        if (keyword.length() <= 1) {
            return "";
        }

        String[] tokens = keyword.split("\\s+");
        return tokens.length == 0 ? "" : tokens[0];
    }
}
