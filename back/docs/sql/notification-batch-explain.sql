-- MEDEAT medication notification batch EXPLAIN checklist.
-- Run on MySQL 8.x after Flyway migrations and seed data are applied.

SET @now = '2026-07-12 08:05:00.000000';
SET @max_attempts = 3;
SET @batch_size = 100;

-- 1. Confirm available indexes.
SHOW INDEX FROM medication_notification_send;

-- 2. sendNotificationStep candidate lookup.
-- Expected useful indexes:
-- - idx_med_notification_due_status
-- - idx_med_notification_schedule_status
EXPLAIN FORMAT=JSON
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
  AND attempt_count < @max_attempts
  AND scheduled_at <= @now
  AND (next_retry_at IS NULL OR next_retry_at <= @now)
ORDER BY scheduled_at ASC, notification_send_id ASC
LIMIT 100;

-- MySQL 8.0.18+ only. Use this for actual execution time and row scan count.
EXPLAIN ANALYZE
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
  AND attempt_count < @max_attempts
  AND scheduled_at <= @now
  AND (next_retry_at IS NULL OR next_retry_at <= @now)
ORDER BY scheduled_at ASC, notification_send_id ASC
LIMIT 100;

-- 3. PROCESSING claim update.
EXPLAIN FORMAT=JSON
UPDATE medication_notification_send
SET status = 'PROCESSING',
    attempt_count = attempt_count + 1,
    claim_token = '00000000-0000-0000-0000-000000000000',
    claimed_at = @now,
    claimed_by = 'explain-worker',
    claim_expires_at = DATE_ADD(@now, INTERVAL 10 MINUTE),
    last_attempted_at = @now,
    failure_code = NULL,
    failure_reason = NULL
WHERE notification_send_id = 1
  AND status IN ('PENDING', 'RETRY')
  AND attempt_count < @max_attempts
  AND scheduled_at <= @now
  AND (next_retry_at IS NULL OR next_retry_at <= @now);

-- 4. Claim-token guarded result update.
EXPLAIN FORMAT=JSON
UPDATE medication_notification_send
SET status = 'SENT',
    sent_at = @now,
    next_retry_at = NULL,
    claim_token = NULL,
    claimed_at = NULL,
    claimed_by = NULL,
    claim_expires_at = NULL,
    failure_code = NULL,
    failure_reason = NULL
WHERE notification_send_id = 1
  AND status = 'PROCESSING'
  AND claim_token = '00000000-0000-0000-0000-000000000000';

-- 5. stale PROCESSING recovery.
EXPLAIN FORMAT=JSON
UPDATE medication_notification_send
SET status = CASE
        WHEN attempt_count >= @max_attempts THEN 'FAILED'
        ELSE 'RETRY'
    END,
    next_retry_at = CASE
        WHEN attempt_count >= @max_attempts THEN NULL
        ELSE DATE_ADD(@now, INTERVAL 1 MINUTE)
    END,
    failure_code = 'LEASE_EXPIRED',
    failure_reason = 'Processing lease expired before completion',
    claim_token = NULL,
    claimed_at = NULL,
    claimed_by = NULL,
    claim_expires_at = NULL
WHERE status = 'PROCESSING'
  AND claim_expires_at < @now;

-- 6. Status distribution for performance result reporting.
SELECT status, COUNT(*) AS row_count
FROM medication_notification_send
GROUP BY status
ORDER BY status;
