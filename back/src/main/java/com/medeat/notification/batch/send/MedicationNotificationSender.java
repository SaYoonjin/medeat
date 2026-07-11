package com.medeat.notification.batch.send;

import com.medeat.notification.batch.outbox.MedicationNotificationSendCandidate;
import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class MedicationNotificationSender {

    private static final String NO_ACTIVE_SUBSCRIPTION = "NO_ACTIVE_SUBSCRIPTION";

    private final MedicationNotificationSendDao notificationSendDao;
    private final MedicationNotificationDispatchService dispatchService;
    private final int maxAttempts;
    private final int batchSize;
    private final int leaseMinutes;
    private final String workerId;

    public MedicationNotificationSender(
            MedicationNotificationSendDao notificationSendDao,
            MedicationNotificationDispatchService dispatchService,
            @Value("${medeat.notification.batch.send.max-attempts:3}") int maxAttempts,
            @Value("${medeat.notification.batch.send.batch-size:100}") int batchSize,
            @Value("${medeat.notification.batch.send.lease-minutes:10}") int leaseMinutes,
            @Value("${medeat.notification.batch.send.worker-id:}") String configuredWorkerId
    ) {
        this.notificationSendDao = notificationSendDao;
        this.dispatchService = dispatchService;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.leaseMinutes = leaseMinutes;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? defaultWorkerId()
                : configuredWorkerId;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SendNotificationResult sendDueNotifications(LocalDateTime now) {
        List<MedicationNotificationSendCandidate> candidates =
                notificationSendDao.findDispatchCandidates(now, maxAttempts, batchSize);

        int claimedCount = 0;
        int sentCount = 0;
        int retryCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int claimConflictCount = 0;
        int staleResultCount = 0;

        for (MedicationNotificationSendCandidate candidate : candidates) {
            String claimToken = UUID.randomUUID().toString();
            boolean claimed = notificationSendDao.claimForProcessing(
                    candidate.notificationSendId(),
                    claimToken,
                    workerId,
                    now,
                    now.plusMinutes(leaseMinutes),
                    maxAttempts
            );

            if (!claimed) {
                claimConflictCount++;
                continue;
            }

            claimedCount++;
            WebPushDispatchResult dispatchResult = dispatchService.dispatch(candidate);
            ResultDecision decision = decide(candidate, dispatchResult, now);
            boolean updated = switch (decision.status) {
                case SENT -> notificationSendDao.markSent(candidate.notificationSendId(), claimToken, now);
                case RETRY -> notificationSendDao.markRetry(
                        candidate.notificationSendId(),
                        claimToken,
                        decision.nextRetryAt,
                        decision.failureCode,
                        decision.failureReason
                );
                case FAILED -> notificationSendDao.markFailed(
                        candidate.notificationSendId(),
                        claimToken,
                        decision.failureCode,
                        decision.failureReason
                );
                case SKIPPED -> notificationSendDao.markSkipped(
                        candidate.notificationSendId(),
                        claimToken,
                        decision.failureCode,
                        decision.failureReason
                );
            };

            if (!updated) {
                staleResultCount++;
                continue;
            }

            switch (decision.status) {
                case SENT -> sentCount++;
                case RETRY -> retryCount++;
                case FAILED -> failedCount++;
                case SKIPPED -> skippedCount++;
            }
        }

        return new SendNotificationResult(
                candidates.size(),
                claimedCount,
                sentCount,
                retryCount,
                failedCount,
                skippedCount,
                claimConflictCount,
                staleResultCount
        );
    }

    private ResultDecision decide(
            MedicationNotificationSendCandidate candidate,
            WebPushDispatchResult dispatchResult,
            LocalDateTime now
    ) {
        if (dispatchResult.totalCount() == 0) {
            return ResultDecision.skipped(NO_ACTIVE_SUBSCRIPTION, "No active WebPush subscription");
        }
        if (dispatchResult.hasAnySuccess()) {
            return ResultDecision.sent();
        }

        WebPushFailure firstFailure = dispatchResult.failures().isEmpty()
                ? new WebPushFailure(null, "WEB_PUSH_UNKNOWN_FAILURE", "WebPush dispatch failed", true)
                : dispatchResult.failures().get(0);
        int currentAttempt = candidate.attemptCount() + 1;

        if (dispatchResult.hasRetryableFailure() && currentAttempt < maxAttempts) {
            return ResultDecision.retry(
                    nextRetryAt(now, currentAttempt),
                    firstFailure.failureCode(),
                    firstFailure.failureReason()
            );
        }

        return ResultDecision.failed(firstFailure.failureCode(), firstFailure.failureReason());
    }

    private LocalDateTime nextRetryAt(LocalDateTime now, int currentAttempt) {
        if (currentAttempt <= 1) {
            return now.plusMinutes(1);
        }
        return now.plusMinutes(5);
    }

    private String defaultWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "medeat-batch-worker";
        }
    }

    private enum ResultStatus {
        SENT,
        RETRY,
        FAILED,
        SKIPPED
    }

    private record ResultDecision(
            ResultStatus status,
            LocalDateTime nextRetryAt,
            String failureCode,
            String failureReason
    ) {
        static ResultDecision sent() {
            return new ResultDecision(ResultStatus.SENT, null, null, null);
        }

        static ResultDecision retry(LocalDateTime nextRetryAt, String failureCode, String failureReason) {
            return new ResultDecision(ResultStatus.RETRY, nextRetryAt, failureCode, failureReason);
        }

        static ResultDecision failed(String failureCode, String failureReason) {
            return new ResultDecision(ResultStatus.FAILED, null, failureCode, failureReason);
        }

        static ResultDecision skipped(String failureCode, String failureReason) {
            return new ResultDecision(ResultStatus.SKIPPED, null, failureCode, failureReason);
        }
    }
}
