package com.medeat.notification.batch.send;

public record WebPushFailure(
        Long subscriptionId,
        String failureCode,
        String failureReason,
        boolean retryable
) {
}
