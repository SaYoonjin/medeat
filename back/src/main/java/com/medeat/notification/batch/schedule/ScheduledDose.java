package com.medeat.notification.batch.schedule;

import java.time.LocalDateTime;

public record ScheduledDose(
        Long medicationId,
        Long userId,
        LocalDateTime scheduledAt,
        int doseSequence,
        ScheduleSource scheduleSource
) {
}
