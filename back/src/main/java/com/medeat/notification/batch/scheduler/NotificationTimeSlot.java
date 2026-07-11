package com.medeat.notification.batch.scheduler;

import java.time.LocalDateTime;

public record NotificationTimeSlot(
        LocalDateTime slotStart,
        LocalDateTime slotEnd
) {
}
