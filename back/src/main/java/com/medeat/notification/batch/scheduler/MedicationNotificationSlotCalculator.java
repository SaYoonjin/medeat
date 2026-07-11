package com.medeat.notification.batch.scheduler;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class MedicationNotificationSlotCalculator {

    public NotificationTimeSlot previousSlot(LocalDateTime now, int slotMinutes) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        if (slotMinutes <= 0 || 60 % slotMinutes != 0) {
            throw new IllegalArgumentException("slotMinutes must be a positive divisor of 60");
        }

        LocalDateTime truncatedNow = now.truncatedTo(ChronoUnit.MINUTES);
        int alignedMinute = (truncatedNow.getMinute() / slotMinutes) * slotMinutes;
        LocalDateTime slotEnd = truncatedNow.withMinute(alignedMinute);
        LocalDateTime slotStart = slotEnd.minusMinutes(slotMinutes);
        return new NotificationTimeSlot(slotStart, slotEnd);
    }
}
