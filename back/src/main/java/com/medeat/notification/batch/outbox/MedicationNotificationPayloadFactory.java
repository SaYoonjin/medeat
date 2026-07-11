package com.medeat.notification.batch.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.notification.batch.schedule.ScheduledDose;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MedicationNotificationPayloadFactory {

    private static final String DEFAULT_DRUG_NAME = "Medication";
    private static final String TITLE = "Medication reminder";

    private final ObjectMapper objectMapper;

    public MedicationNotificationPayloadFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MedicationNotificationPayload create(MedicationDto medication, ScheduledDose dose) {
        String drugName = valueOrDefault(medication.getDrugName(), DEFAULT_DRUG_NAME);
        String body = drugName + " dose is due.";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", TITLE);
        payload.put("body", body);
        payload.put("medicationId", dose.medicationId());
        payload.put("doseSequence", dose.doseSequence());
        payload.put("scheduledAt", dose.scheduledAt().toString());

        try {
            return new MedicationNotificationPayload(
                    TITLE,
                    body,
                    objectMapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to create medication notification payload.", e);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
