package com.medeat.notification.batch.outbox;

public record MedicationNotificationPayload(
        String title,
        String body,
        String payloadJson
) {
}
