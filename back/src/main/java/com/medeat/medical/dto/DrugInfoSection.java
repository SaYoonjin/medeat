package com.medeat.medical.dto;

public enum DrugInfoSection {
    EFFICACY("효능"),
    USAGE("복용법"),
    WARNING("경고"),
    PRECAUTION("주의사항"),
    INTERACTION("상호작용"),
    SIDE_EFFECT("부작용"),
    STORAGE("보관방법");

    private final String label;

    DrugInfoSection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public String getValue(DrugInfoDto drug) {
        return switch (this) {
            case EFFICACY -> drug.getEfcyQesitm();
            case USAGE -> drug.getUseMethodQesitm();
            case WARNING -> drug.getAtpnWarnQesitm();
            case PRECAUTION -> drug.getAtpnQesitm();
            case INTERACTION -> drug.getIntrcQesitm();
            case SIDE_EFFECT -> drug.getSeQesitm();
            case STORAGE -> drug.getDepositMethodQesitm();
        };
    }
}
