package com.medeat.notification.batch.scheduler;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MedicationNotificationSlotCalculatorTest {

    private final MedicationNotificationSlotCalculator calculator = new MedicationNotificationSlotCalculator();

    @Test
    void calculatesPreviousFiveMinuteSlotAtBoundary() {
        NotificationTimeSlot slot = calculator.previousSlot(
                LocalDateTime.of(2026, 7, 12, 8, 5, 0),
                5
        );

        assertThat(slot.slotStart()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 0));
        assertThat(slot.slotEnd()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 5));
    }

    @Test
    void alignsCurrentTimeDownToPreviousSlotEnd() {
        NotificationTimeSlot slot = calculator.previousSlot(
                LocalDateTime.of(2026, 7, 12, 8, 7, 30),
                5
        );

        assertThat(slot.slotStart()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 0));
        assertThat(slot.slotEnd()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 5));
    }

    @Test
    void calculatesPreviousSlotAcrossHourBoundary() {
        NotificationTimeSlot slot = calculator.previousSlot(
                LocalDateTime.of(2026, 7, 12, 8, 0, 0),
                5
        );

        assertThat(slot.slotStart()).isEqualTo(LocalDateTime.of(2026, 7, 12, 7, 55));
        assertThat(slot.slotEnd()).isEqualTo(LocalDateTime.of(2026, 7, 12, 8, 0));
    }

    @Test
    void rejectsInvalidSlotMinutes() {
        assertThatThrownBy(() -> calculator.previousSlot(LocalDateTime.of(2026, 7, 12, 8, 0), 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("slotMinutes must be a positive divisor of 60");
    }
}
