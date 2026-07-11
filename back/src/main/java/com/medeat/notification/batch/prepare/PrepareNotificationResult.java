package com.medeat.notification.batch.prepare;

public record PrepareNotificationResult(
        int medicationCandidateCount,
        int dueDoseCount,
        int outboxInsertAttemptCount,
        int outboxCreatedCount,
        int outboxDuplicateCount
) {
}
