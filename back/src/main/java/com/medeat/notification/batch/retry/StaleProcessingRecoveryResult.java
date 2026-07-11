package com.medeat.notification.batch.retry;

public record StaleProcessingRecoveryResult(
        int recoveredCount
) {
}
