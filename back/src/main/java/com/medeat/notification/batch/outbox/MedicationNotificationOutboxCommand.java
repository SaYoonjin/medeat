package com.medeat.notification.batch.outbox;

import java.time.LocalDateTime;

public record MedicationNotificationOutboxCommand(
        Long userId,
        Long medicationId,
        LocalDateTime scheduledAt,
        int doseSequence,
        String notificationTitle,
        String notificationBody,
        String payloadJson,
        Long originJobExecutionId
) {
}
