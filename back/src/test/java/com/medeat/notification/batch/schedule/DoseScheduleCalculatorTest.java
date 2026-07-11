package com.medeat.notification.batch.schedule;

import com.medeat.medical.dto.MedicationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoseScheduleCalculatorTest {

    private final DoseScheduleCalculator calculator = new DoseScheduleCalculator();

    @Test
    void usesLegacyExplicitTimesWhenMultipleValidTimesExist() {
        MedicationDto medication = medication("08:00,12:30,20:00", 6, 3);

        List<ScheduledDose> doses = calculator.calculate(
                medication,
                LocalDateTime.of(2026, 7, 11, 12, 30),
                LocalDateTime.of(2026, 7, 11, 12, 35)
        );

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).scheduledAt()).isEqualTo(LocalDateTime.of(2026, 7, 11, 12, 30));
        assertThat(doses.get(0).doseSequence()).isEqualTo(2);
        assertThat(doses.get(0).scheduleSource()).isEqualTo(ScheduleSource.LEGACY_EXPLICIT_TIMES);
    }

    @Test
    void calculatesIntervalDosesWhenSingleStartTimeExists() {
        MedicationDto medication = medication("08:00", 8, 3);

        List<ScheduledDose> doses = calculator.calculate(
                medication,
                LocalDateTime.of(2026, 7, 11, 16, 0),
                LocalDateTime.of(2026, 7, 11, 16, 5)
        );

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).scheduledAt()).isEqualTo(LocalDateTime.of(2026, 7, 11, 16, 0));
        assertThat(doses.get(0).doseSequence()).isEqualTo(2);
        assertThat(doses.get(0).scheduleSource()).isEqualTo(ScheduleSource.INTERVAL_CALCULATED);
    }

    @Test
    void includesSlotStartAndExcludesSlotEnd() {
        MedicationDto medication = medication("08:00,08:05", null, 0);

        List<ScheduledDose> doses = calculator.calculate(
                medication,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                LocalDateTime.of(2026, 7, 11, 8, 5)
        );

        assertThat(doses)
                .extracting(ScheduledDose::scheduledAt)
                .containsExactly(LocalDateTime.of(2026, 7, 11, 8, 0));
    }

    @Test
    void calculatesCrossMidnightIntervalDoseFromPreviousPlanDate() {
        MedicationDto medication = medication("08:00", 8, 3);

        List<ScheduledDose> doses = calculator.calculate(
                medication,
                LocalDateTime.of(2026, 7, 12, 0, 0),
                LocalDateTime.of(2026, 7, 12, 0, 5)
        );

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).scheduledAt()).isEqualTo(LocalDateTime.of(2026, 7, 12, 0, 0));
        assertThat(doses.get(0).doseSequence()).isEqualTo(3);
        assertThat(doses.get(0).scheduleSource()).isEqualTo(ScheduleSource.INTERVAL_CALCULATED);
    }

    @Test
    void returnsEmptyWhenSchedulePolicyIsInvalid() {
        MedicationDto medication = medication("08:00", null, 3);

        List<ScheduledDose> doses = calculator.calculate(
                medication,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                LocalDateTime.of(2026, 7, 11, 8, 5)
        );

        assertThat(doses).isEmpty();
    }

    private MedicationDto medication(String intakeTime, Integer intervalHour, int dailyCount) {
        MedicationDto medication = new MedicationDto();
        medication.setMedicationId(10L);
        medication.setUserId(1L);
        medication.setIntakeTime(intakeTime);
        medication.setIntervalHour(intervalHour);
        medication.setDailyCount(dailyCount);
        return medication;
    }
}
