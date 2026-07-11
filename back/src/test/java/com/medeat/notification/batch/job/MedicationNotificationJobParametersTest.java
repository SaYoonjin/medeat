package com.medeat.notification.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MedicationNotificationJobParametersTest {

    @Test
    void parsesRequiredSlotParameters() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(MedicationNotificationJobParameterNames.SLOT_START, "2026-07-12T08:00:00")
                .addString(MedicationNotificationJobParameterNames.SLOT_END, "2026-07-12T08:05:00")
                .addString(MedicationNotificationJobParameterNames.ZONE_ID, "Asia/Seoul")
                .toJobParameters();

        MedicationNotificationJobParameters result = MedicationNotificationJobParameters.from(jobParameters);

        assertThat(result.slotStart()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 0));
        assertThat(result.slotEnd()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 5));
        assertThat(result.zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void rejectsMissingRequiredParameter() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(MedicationNotificationJobParameterNames.SLOT_START, "2026-07-12T08:00:00")
                .addString(MedicationNotificationJobParameterNames.ZONE_ID, "Asia/Seoul")
                .toJobParameters();

        assertThatThrownBy(() -> MedicationNotificationJobParameters.from(jobParameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required JobParameter: slotEnd");
    }

    @Test
    void rejectsInvalidSlotRange() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(MedicationNotificationJobParameterNames.SLOT_START, "2026-07-12T08:05:00")
                .addString(MedicationNotificationJobParameterNames.SLOT_END, "2026-07-12T08:00:00")
                .addString(MedicationNotificationJobParameterNames.ZONE_ID, "Asia/Seoul")
                .toJobParameters();

        assertThatThrownBy(() -> MedicationNotificationJobParameters.from(jobParameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("slotStart must be before slotEnd");
    }
}
