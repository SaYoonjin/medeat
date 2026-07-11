package com.medeat.notification.batch.send;

import com.medeat.notification.batch.outbox.MedicationNotificationSendCandidate;
import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicationNotificationSenderTest {

    private final MedicationNotificationSendDao notificationSendDao = mock(MedicationNotificationSendDao.class);
    private final MedicationNotificationDispatchService dispatchService = mock(MedicationNotificationDispatchService.class);
    private final MedicationNotificationSender sender = new MedicationNotificationSender(
            notificationSendDao,
            dispatchService,
            3,
            100,
            10,
            "worker-1"
    );

    @Test
    void marksSentWhenAnySubscriptionSucceeds() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(0);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(true);
        when(dispatchService.dispatch(candidate)).thenReturn(new WebPushDispatchResult(2, 1, 1, 0, List.of(
                new WebPushFailure(2L, "WEB_PUSH_RETRYABLE_500", "temporary", true)
        )));
        when(notificationSendDao.markSent(eq(100L), any(), eq(now))).thenReturn(true);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.retryCount()).isZero();
        verify(notificationSendDao).markSent(eq(100L), any(), eq(now));
    }

    @Test
    void marksRetryWhenAllSubscriptionsFailTemporarilyBeforeMaxAttempts() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(0);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(true);
        when(dispatchService.dispatch(candidate)).thenReturn(new WebPushDispatchResult(1, 0, 1, 0, List.of(
                new WebPushFailure(1L, "WEB_PUSH_TIMEOUT", "timeout", true)
        )));
        when(notificationSendDao.markRetry(eq(100L), any(), eq(now.plusMinutes(1)),
                eq("WEB_PUSH_TIMEOUT"), eq("timeout"))).thenReturn(true);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.retryCount()).isEqualTo(1);
        verify(notificationSendDao).markRetry(eq(100L), any(), eq(now.plusMinutes(1)),
                eq("WEB_PUSH_TIMEOUT"), eq("timeout"));
    }

    @Test
    void marksFailedWhenRetryableFailureReachesMaxAttempts() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(2);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(true);
        when(dispatchService.dispatch(candidate)).thenReturn(new WebPushDispatchResult(1, 0, 1, 0, List.of(
                new WebPushFailure(1L, "WEB_PUSH_TIMEOUT", "timeout", true)
        )));
        when(notificationSendDao.markFailed(eq(100L), any(), eq("WEB_PUSH_TIMEOUT"), eq("timeout"))).thenReturn(true);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.failedCount()).isEqualTo(1);
        verify(notificationSendDao).markFailed(eq(100L), any(), eq("WEB_PUSH_TIMEOUT"), eq("timeout"));
        verify(notificationSendDao, never()).markRetry(any(), any(), any(), any(), any());
    }

    @Test
    void marksSkippedWhenNoActiveSubscriptionExists() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(0);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(true);
        when(dispatchService.dispatch(candidate)).thenReturn(new WebPushDispatchResult(0, 0, 0, 0, List.of()));
        when(notificationSendDao.markSkipped(eq(100L), any(), eq("NO_ACTIVE_SUBSCRIPTION"),
                eq("No active WebPush subscription"))).thenReturn(true);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.skippedCount()).isEqualTo(1);
        verify(notificationSendDao).markSkipped(eq(100L), any(), eq("NO_ACTIVE_SUBSCRIPTION"),
                eq("No active WebPush subscription"));
    }

    @Test
    void doesNotDispatchWhenClaimFails() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(0);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(false);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.claimConflictCount()).isEqualTo(1);
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void countsStaleResultWhenClaimTokenNoLongerMatchesOnResultUpdate() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        MedicationNotificationSendCandidate candidate = candidate(0);
        when(notificationSendDao.findDispatchCandidates(now, 3, 100)).thenReturn(List.of(candidate));
        when(notificationSendDao.claimForProcessing(eq(100L), any(), eq("worker-1"), eq(now),
                eq(now.plusMinutes(10)), eq(3))).thenReturn(true);
        when(dispatchService.dispatch(candidate)).thenReturn(new WebPushDispatchResult(1, 1, 0, 0, List.of()));
        when(notificationSendDao.markSent(eq(100L), any(), eq(now))).thenReturn(false);

        SendNotificationResult result = sender.sendDueNotifications(now);

        assertThat(result.claimedCount()).isEqualTo(1);
        assertThat(result.staleResultCount()).isEqualTo(1);
        assertThat(result.sentCount()).isZero();
    }

    private MedicationNotificationSendCandidate candidate(int attemptCount) {
        return new MedicationNotificationSendCandidate(
                100L,
                1L,
                10L,
                LocalDateTime.of(2026, 7, 12, 8, 0),
                1,
                attemptCount,
                "Medication reminder",
                "Tylenol dose is due.",
                "{\"title\":\"Medication reminder\"}"
        );
    }
}
