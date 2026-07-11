package com.medeat.notification.batch.send;

public record SendNotificationResult(
        int candidateCount,
        int claimedCount,
        int sentCount,
        int retryCount,
        int failedCount,
        int skippedCount,
        int claimConflictCount,
        int staleResultCount
) {
}
