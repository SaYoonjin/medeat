package com.medeat.notification.batch.send;

import java.util.List;

public record WebPushDispatchResult(
        int totalCount,
        int successCount,
        int retryableFailureCount,
        int terminalFailureCount,
        List<WebPushFailure> failures
) {

    public boolean hasAnySuccess() {
        return successCount > 0;
    }

    public boolean hasRetryableFailure() {
        return retryableFailureCount > 0;
    }
}
