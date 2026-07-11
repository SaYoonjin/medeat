package com.medeat.notification.batch.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicationNotificationSendDaoTest {

    private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final MedicationNotificationSendDao dao = new MedicationNotificationSendDao(jdbcTemplate);

    @Test
    void insertPendingUsesIdempotentOutboxInsert() {
        MedicationNotificationOutboxCommand command = command();
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        int result = dao.insertPending(command);

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sqlCaptor.getValue()).contains("'PENDING'");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("userId")).isEqualTo(1L);
        assertThat(params.getValue("medicationId")).isEqualTo(10L);
        assertThat(params.getValue("doseSequence")).isEqualTo(2);
        assertThat(params.getValue("payloadJson")).isEqualTo("{\"ok\":true}");
    }

    @Test
    void insertPendingBatchUsesSameInsertSql() {
        MedicationNotificationOutboxCommand command = command();
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[] {1});

        int[] result = dao.insertPendingBatch(List.of(command));

        assertThat(result).containsExactly(1);
        verify(jdbcTemplate).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void findDispatchCandidatesQueriesOnlyDuePendingOrRetryRows() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());

        List<MedicationNotificationSendCandidate> result = dao.findDispatchCandidates(now, 3, 100);

        assertThat(result).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

        assertThat(sqlCaptor.getValue()).contains("status IN ('PENDING', 'RETRY')");
        assertThat(sqlCaptor.getValue()).contains("attempt_count < :maxAttempts");
        assertThat(sqlCaptor.getValue()).contains("scheduled_at <= :now");
        assertThat(sqlCaptor.getValue()).contains("next_retry_at IS NULL OR next_retry_at <= :now");
        assertThat(sqlCaptor.getValue()).contains("ORDER BY scheduled_at ASC, notification_send_id ASC");
        assertThat(sqlCaptor.getValue()).contains("LIMIT :limit");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("now")).isEqualTo(now);
        assertThat(params.getValue("maxAttempts")).isEqualTo(3);
        assertThat(params.getValue("limit")).isEqualTo(100);
    }

    @Test
    void claimForProcessingUpdatesOnlyClaimableRows() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 5);
        LocalDateTime claimExpiresAt = LocalDateTime.of(2026, 7, 12, 8, 15);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        boolean claimed = dao.claimForProcessing(
                100L,
                "claim-token",
                "worker-1",
                now,
                claimExpiresAt,
                3
        );

        assertThat(claimed).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("status = 'PROCESSING'");
        assertThat(sqlCaptor.getValue()).contains("attempt_count = attempt_count + 1");
        assertThat(sqlCaptor.getValue()).contains("claim_token = :claimToken");
        assertThat(sqlCaptor.getValue()).contains("claim_expires_at = :claimExpiresAt");
        assertThat(sqlCaptor.getValue()).contains("notification_send_id = :notificationSendId");
        assertThat(sqlCaptor.getValue()).contains("status IN ('PENDING', 'RETRY')");
        assertThat(sqlCaptor.getValue()).contains("attempt_count < :maxAttempts");
        assertThat(sqlCaptor.getValue()).contains("scheduled_at <= :now");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("notificationSendId")).isEqualTo(100L);
        assertThat(params.getValue("claimToken")).isEqualTo("claim-token");
        assertThat(params.getValue("workerId")).isEqualTo("worker-1");
        assertThat(params.getValue("now")).isEqualTo(now);
        assertThat(params.getValue("claimExpiresAt")).isEqualTo(claimExpiresAt);
        assertThat(params.getValue("maxAttempts")).isEqualTo(3);
    }

    @Test
    void claimForProcessingReturnsFalseWhenAnotherWorkerAlreadyClaimed() {
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        boolean claimed = dao.claimForProcessing(
                100L,
                "claim-token",
                "worker-1",
                LocalDateTime.of(2026, 7, 12, 8, 5),
                LocalDateTime.of(2026, 7, 12, 8, 15),
                3
        );

        assertThat(claimed).isFalse();
    }

    @Test
    void markSentStoresResultOnlyForMatchingClaimToken() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 6);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        boolean updated = dao.markSent(100L, "claim-token", now);

        assertThat(updated).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("status = 'SENT'");
        assertThat(sqlCaptor.getValue()).contains("sent_at = :now");
        assertThat(sqlCaptor.getValue()).contains("next_retry_at = NULL");
        assertThat(sqlCaptor.getValue()).contains("claim_token = NULL");
        assertThat(sqlCaptor.getValue()).contains("status = 'PROCESSING'");
        assertThat(sqlCaptor.getValue()).contains("claim_token = :claimToken");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("notificationSendId")).isEqualTo(100L);
        assertThat(params.getValue("claimToken")).isEqualTo("claim-token");
        assertThat(params.getValue("now")).isEqualTo(now);
    }

    @Test
    void markRetryStoresBackoffAndFailureForMatchingClaimToken() {
        LocalDateTime nextRetryAt = LocalDateTime.of(2026, 7, 12, 8, 10);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        boolean updated = dao.markRetry(
                100L,
                "claim-token",
                nextRetryAt,
                "WEB_PUSH_TIMEOUT",
                "WebPush request timed out"
        );

        assertThat(updated).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("status = 'RETRY'");
        assertThat(sqlCaptor.getValue()).contains("next_retry_at = :nextRetryAt");
        assertThat(sqlCaptor.getValue()).contains("failure_code = :failureCode");
        assertThat(sqlCaptor.getValue()).contains("failure_reason = :failureReason");
        assertThat(sqlCaptor.getValue()).contains("status = 'PROCESSING'");
        assertThat(sqlCaptor.getValue()).contains("claim_token = :claimToken");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("notificationSendId")).isEqualTo(100L);
        assertThat(params.getValue("claimToken")).isEqualTo("claim-token");
        assertThat(params.getValue("nextRetryAt")).isEqualTo(nextRetryAt);
        assertThat(params.getValue("failureCode")).isEqualTo("WEB_PUSH_TIMEOUT");
        assertThat(params.getValue("failureReason")).isEqualTo("WebPush request timed out");
    }

    @Test
    void markFailedClearsClaimAndStoresFailureForMatchingClaimToken() {
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        boolean updated = dao.markFailed(
                100L,
                "claim-token",
                "INVALID_SUBSCRIPTION",
                "Subscription is expired"
        );

        assertThat(updated).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("status = 'FAILED'");
        assertThat(sqlCaptor.getValue()).contains("next_retry_at = NULL");
        assertThat(sqlCaptor.getValue()).contains("claim_token = NULL");
        assertThat(sqlCaptor.getValue()).contains("failure_code = :failureCode");
        assertThat(sqlCaptor.getValue()).contains("failure_reason = :failureReason");
        assertThat(sqlCaptor.getValue()).contains("status = 'PROCESSING'");
        assertThat(sqlCaptor.getValue()).contains("claim_token = :claimToken");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("failureCode")).isEqualTo("INVALID_SUBSCRIPTION");
        assertThat(params.getValue("failureReason")).isEqualTo("Subscription is expired");
    }

    @Test
    void markSkippedClearsClaimAndStoresSkipReasonForMatchingClaimToken() {
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        boolean updated = dao.markSkipped(
                100L,
                "claim-token",
                "NO_ACTIVE_SUBSCRIPTION",
                "No active WebPush subscription"
        );

        assertThat(updated).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("status = 'SKIPPED'");
        assertThat(sqlCaptor.getValue()).contains("next_retry_at = NULL");
        assertThat(sqlCaptor.getValue()).contains("claim_token = NULL");
        assertThat(sqlCaptor.getValue()).contains("failure_code = :failureCode");
        assertThat(sqlCaptor.getValue()).contains("failure_reason = :failureReason");
        assertThat(sqlCaptor.getValue()).contains("status = 'PROCESSING'");
        assertThat(sqlCaptor.getValue()).contains("claim_token = :claimToken");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("failureCode")).isEqualTo("NO_ACTIVE_SUBSCRIPTION");
        assertThat(params.getValue("failureReason")).isEqualTo("No active WebPush subscription");
    }

    @Test
    void resultUpdateReturnsFalseWhenClaimTokenNoLongerMatches() {
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        boolean updated = dao.markSent(
                100L,
                "old-claim-token",
                LocalDateTime.of(2026, 7, 12, 8, 6)
        );

        assertThat(updated).isFalse();
    }

    @Test
    void recoverStaleProcessingClearsClaimAndMovesRowsToRetryOrFailed() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 8, 20);
        LocalDateTime nextRetryAt = LocalDateTime.of(2026, 7, 12, 8, 21);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(2);

        int recoveredCount = dao.recoverStaleProcessing(now, 3, nextRetryAt);

        assertThat(recoveredCount).isEqualTo(2);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("WHEN attempt_count >= :maxAttempts THEN 'FAILED'");
        assertThat(sqlCaptor.getValue()).contains("ELSE 'RETRY'");
        assertThat(sqlCaptor.getValue()).contains("failure_code = 'LEASE_EXPIRED'");
        assertThat(sqlCaptor.getValue()).contains("claim_token = NULL");
        assertThat(sqlCaptor.getValue()).contains("claim_expires_at < :now");

        SqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("now")).isEqualTo(now);
        assertThat(params.getValue("maxAttempts")).isEqualTo(3);
        assertThat(params.getValue("nextRetryAt")).isEqualTo(nextRetryAt);
    }

    private MedicationNotificationOutboxCommand command() {
        return new MedicationNotificationOutboxCommand(
                1L,
                10L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                2,
                "Medication reminder",
                "Tylenol dose is due.",
                "{\"ok\":true}",
                100L
        );
    }
}
