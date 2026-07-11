package com.medeat.notification.batch.outbox;

import java.time.LocalDateTime;

public record MedicationNotificationSendCandidate(
        Long notificationSendId,
        Long userId,
        Long medicationId,
        LocalDateTime scheduledAt,
        int doseSequence,
        int attemptCount,
        String notificationTitle,
        String notificationBody,
        String payloadJson
) {
}
