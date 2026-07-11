package com.medeat.notification.batch.outbox;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MedicationNotificationSendDao {

    private static final String INSERT_PENDING_SQL = """
            INSERT INTO medication_notification_send (
                user_id,
                medication_id,
                scheduled_at,
                dose_sequence,
                status,
                attempt_count,
                notification_title,
                notification_body,
                payload_json,
                origin_job_execution_id
            )
            VALUES (
                :userId,
                :medicationId,
                :scheduledAt,
                :doseSequence,
                'PENDING',
                0,
                :notificationTitle,
                :notificationBody,
                :payloadJson,
                :originJobExecutionId
            )
            ON DUPLICATE KEY UPDATE
                notification_send_id = notification_send_id
            """;

    private static final String FIND_DISPATCH_CANDIDATES_SQL = """
            SELECT
                notification_send_id,
                user_id,
                medication_id,
                scheduled_at,
                dose_sequence,
                attempt_count,
                notification_title,
                notification_body,
                payload_json
            FROM medication_notification_send
            WHERE status IN ('PENDING', 'RETRY')
              AND attempt_count < :maxAttempts
              AND scheduled_at <= :now
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY scheduled_at ASC, notification_send_id ASC
            LIMIT :limit
            """;

    private static final String CLAIM_FOR_PROCESSING_SQL = """
            UPDATE medication_notification_send
            SET status = 'PROCESSING',
                attempt_count = attempt_count + 1,
                claim_token = :claimToken,
                claimed_at = :now,
                claimed_by = :workerId,
                claim_expires_at = :claimExpiresAt,
                last_attempted_at = :now,
                failure_code = NULL,
                failure_reason = NULL
            WHERE notification_send_id = :notificationSendId
              AND status IN ('PENDING', 'RETRY')
              AND attempt_count < :maxAttempts
              AND scheduled_at <= :now
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            """;

    private static final String MARK_SENT_SQL = """
            UPDATE medication_notification_send
            SET status = 'SENT',
                sent_at = :now,
                next_retry_at = NULL,
                claim_token = NULL,
                claimed_at = NULL,
                claimed_by = NULL,
                claim_expires_at = NULL,
                failure_code = NULL,
                failure_reason = NULL
            WHERE notification_send_id = :notificationSendId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            """;

    private static final String MARK_RETRY_SQL = """
            UPDATE medication_notification_send
            SET status = 'RETRY',
                next_retry_at = :nextRetryAt,
                claim_token = NULL,
                claimed_at = NULL,
                claimed_by = NULL,
                claim_expires_at = NULL,
                failure_code = :failureCode,
                failure_reason = :failureReason
            WHERE notification_send_id = :notificationSendId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE medication_notification_send
            SET status = 'FAILED',
                next_retry_at = NULL,
                claim_token = NULL,
                claimed_at = NULL,
                claimed_by = NULL,
                claim_expires_at = NULL,
                failure_code = :failureCode,
                failure_reason = :failureReason
            WHERE notification_send_id = :notificationSendId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            """;

    private static final String MARK_SKIPPED_SQL = """
            UPDATE medication_notification_send
            SET status = 'SKIPPED',
                next_retry_at = NULL,
                claim_token = NULL,
                claimed_at = NULL,
                claimed_by = NULL,
                claim_expires_at = NULL,
                failure_code = :failureCode,
                failure_reason = :failureReason
            WHERE notification_send_id = :notificationSendId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            """;

    private static final String RECOVER_STALE_PROCESSING_SQL = """
            UPDATE medication_notification_send
            SET status = CASE
                    WHEN attempt_count >= :maxAttempts THEN 'FAILED'
                    ELSE 'RETRY'
                END,
                next_retry_at = CASE
                    WHEN attempt_count >= :maxAttempts THEN NULL
                    ELSE :nextRetryAt
                END,
                failure_code = 'LEASE_EXPIRED',
                failure_reason = 'Processing lease expired before completion',
                claim_token = NULL,
                claimed_at = NULL,
                claimed_by = NULL,
                claim_expires_at = NULL
            WHERE status = 'PROCESSING'
              AND claim_expires_at < :now
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public MedicationNotificationSendDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertPending(MedicationNotificationOutboxCommand command) {
        return jdbcTemplate.update(INSERT_PENDING_SQL, toParameters(command));
    }

    public int[] insertPendingBatch(List<MedicationNotificationOutboxCommand> commands) {
        SqlParameterSource[] batch = commands.stream()
                .map(this::toParameters)
                .toArray(SqlParameterSource[]::new);
        return jdbcTemplate.batchUpdate(INSERT_PENDING_SQL, batch);
    }

    public List<MedicationNotificationSendCandidate> findDispatchCandidates(
            LocalDateTime now,
            int maxAttempts,
            int limit
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", now)
                .addValue("maxAttempts", maxAttempts)
                .addValue("limit", limit);
        return jdbcTemplate.query(FIND_DISPATCH_CANDIDATES_SQL, params, this::toCandidate);
    }

    public boolean claimForProcessing(
            Long notificationSendId,
            String claimToken,
            String workerId,
            LocalDateTime now,
            LocalDateTime claimExpiresAt,
            int maxAttempts
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("notificationSendId", notificationSendId)
                .addValue("claimToken", claimToken)
                .addValue("workerId", workerId)
                .addValue("now", now)
                .addValue("claimExpiresAt", claimExpiresAt)
                .addValue("maxAttempts", maxAttempts);
        return jdbcTemplate.update(CLAIM_FOR_PROCESSING_SQL, params) == 1;
    }

    public boolean markSent(Long notificationSendId, String claimToken, LocalDateTime now) {
        MapSqlParameterSource params = processingResultParameters(notificationSendId, claimToken)
                .addValue("now", now);
        return jdbcTemplate.update(MARK_SENT_SQL, params) == 1;
    }

    public boolean markRetry(
            Long notificationSendId,
            String claimToken,
            LocalDateTime nextRetryAt,
            String failureCode,
            String failureReason
    ) {
        MapSqlParameterSource params = processingResultParameters(notificationSendId, claimToken)
                .addValue("nextRetryAt", nextRetryAt)
                .addValue("failureCode", failureCode)
                .addValue("failureReason", failureReason);
        return jdbcTemplate.update(MARK_RETRY_SQL, params) == 1;
    }

    public boolean markFailed(
            Long notificationSendId,
            String claimToken,
            String failureCode,
            String failureReason
    ) {
        MapSqlParameterSource params = processingResultParameters(notificationSendId, claimToken)
                .addValue("failureCode", failureCode)
                .addValue("failureReason", failureReason);
        return jdbcTemplate.update(MARK_FAILED_SQL, params) == 1;
    }

    public boolean markSkipped(
            Long notificationSendId,
            String claimToken,
            String failureCode,
            String failureReason
    ) {
        MapSqlParameterSource params = processingResultParameters(notificationSendId, claimToken)
                .addValue("failureCode", failureCode)
                .addValue("failureReason", failureReason);
        return jdbcTemplate.update(MARK_SKIPPED_SQL, params) == 1;
    }

    public int recoverStaleProcessing(
            LocalDateTime now,
            int maxAttempts,
            LocalDateTime nextRetryAt
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", now)
                .addValue("maxAttempts", maxAttempts)
                .addValue("nextRetryAt", nextRetryAt);
        return jdbcTemplate.update(RECOVER_STALE_PROCESSING_SQL, params);
    }

    private SqlParameterSource toParameters(MedicationNotificationOutboxCommand command) {
        return new MapSqlParameterSource()
                .addValue("userId", command.userId())
                .addValue("medicationId", command.medicationId())
                .addValue("scheduledAt", command.scheduledAt())
                .addValue("doseSequence", command.doseSequence())
                .addValue("notificationTitle", command.notificationTitle())
                .addValue("notificationBody", command.notificationBody())
                .addValue("payloadJson", command.payloadJson())
                .addValue("originJobExecutionId", command.originJobExecutionId());
    }

    private MedicationNotificationSendCandidate toCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new MedicationNotificationSendCandidate(
                rs.getLong("notification_send_id"),
                rs.getLong("user_id"),
                rs.getLong("medication_id"),
                toLocalDateTime(rs.getTimestamp("scheduled_at")),
                rs.getInt("dose_sequence"),
                rs.getInt("attempt_count"),
                rs.getString("notification_title"),
                rs.getString("notification_body"),
                rs.getString("payload_json")
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private MapSqlParameterSource processingResultParameters(Long notificationSendId, String claimToken) {
        return new MapSqlParameterSource()
                .addValue("notificationSendId", notificationSendId)
                .addValue("claimToken", claimToken);
    }
}
