package com.medeat.notification.batch.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.notification.batch.schedule.ScheduleSource;
import com.medeat.notification.batch.schedule.ScheduledDose;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationNotificationPayloadFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MedicationNotificationPayloadFactory factory =
            new MedicationNotificationPayloadFactory(objectMapper);

    @Test
    void createsPayloadSnapshotFromMedicationAndDose() throws Exception {
        MedicationDto medication = new MedicationDto();
        medication.setDrugName("Tylenol");
        ScheduledDose dose = new ScheduledDose(
                10L,
                1L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                2,
                ScheduleSource.INTERVAL_CALCULATED
        );

        MedicationNotificationPayload payload = factory.create(medication, dose);

        assertThat(payload.title()).isEqualTo("Medication reminder");
        assertThat(payload.body()).isEqualTo("Tylenol dose is due.");

        JsonNode json = objectMapper.readTree(payload.payloadJson());
        assertThat(json.get("title").asText()).isEqualTo("Medication reminder");
        assertThat(json.get("body").asText()).isEqualTo("Tylenol dose is due.");
        assertThat(json.get("medicationId").asLong()).isEqualTo(10L);
        assertThat(json.get("doseSequence").asInt()).isEqualTo(2);
        assertThat(json.get("scheduledAt").asText()).isEqualTo("2026-07-11T08:00");
    }

    @Test
    void usesDefaultDrugNameWhenMedicationNameIsMissing() {
        MedicationDto medication = new MedicationDto();
        ScheduledDose dose = new ScheduledDose(
                10L,
                1L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                1,
                ScheduleSource.LEGACY_EXPLICIT_TIMES
        );

        MedicationNotificationPayload payload = factory.create(medication, dose);

        assertThat(payload.body()).isEqualTo("Medication dose is due.");
    }
}
