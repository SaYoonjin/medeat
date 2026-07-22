-- MEDEAT medication notification batch performance seed.
-- Intended for a disposable local/performance database.
-- Adjust @user_count and @medication_per_user before running.

SET @user_count = 10000;
SET @medication_per_user = 5;
SET @slot_start = '2026-07-12 08:00:00.000000';
SET @slot_end = '2026-07-12 08:05:00.000000';

-- 1. Users.
INSERT INTO `user` (
    login_id,
    password,
    name,
    nickname,
    email,
    push_enabled,
    created_at
)
SELECT
    CONCAT('batch_user_', n.seq),
    'password',
    CONCAT('Batch User ', n.seq),
    CONCAT('batch', n.seq),
    CONCAT('batch_user_', n.seq, '@example.com'),
    1,
    NOW()
FROM (
    WITH RECURSIVE seq AS (
        SELECT 1 AS seq
        UNION ALL
        SELECT seq + 1 FROM seq WHERE seq < @user_count
    )
    SELECT seq FROM seq
) n
ON DUPLICATE KEY UPDATE login_id = login_id;

-- 2. Medication registrations.
-- Each user gets @medication_per_user rows. The first dose is 08:00 and interval policy generates later doses.
INSERT INTO medication (
    user_id,
    item_seq,
    drug_name,
    ingredient,
    dose,
    intake_time,
    interval_hour,
    daily_count,
    recommended
)
SELECT
    u.user_id,
    100000 + m.seq,
    CONCAT('BatchDrug-', m.seq),
    'BatchIngredient',
    '1 tablet',
    '08:00',
    8,
    3,
    'Y'
FROM `user` u
JOIN (
    WITH RECURSIVE seq AS (
        SELECT 1 AS seq
        UNION ALL
        SELECT seq + 1 FROM seq WHERE seq < @medication_per_user
    )
    SELECT seq FROM seq
) m
WHERE u.login_id LIKE 'batch_user_%';

-- 3. Push subscriptions.
INSERT INTO push_subscription (
    user_id,
    endpoint,
    p256dh,
    auth
)
SELECT
    u.user_id,
    CONCAT('https://push.example.com/subscription/', u.user_id),
    'test-p256dh',
    'test-auth'
FROM `user` u
WHERE u.login_id LIKE 'batch_user_%'
ON DUPLICATE KEY UPDATE endpoint = endpoint;

-- 4. Pre-created Outbox rows for sendNotificationStep and index EXPLAIN.
-- Use this when measuring send candidate lookup and claim performance without running prepare first.
INSERT INTO medication_notification_send (
    user_id,
    medication_id,
    scheduled_at,
    dose_sequence,
    status,
    attempt_count,
    next_retry_at,
    notification_title,
    notification_body,
    payload_json
)
SELECT
    m.user_id,
    m.medication_id,
    @slot_start,
    1,
    CASE
        WHEN MOD(m.medication_id, 20) = 0 THEN 'RETRY'
        ELSE 'PENDING'
    END,
    CASE
        WHEN MOD(m.medication_id, 20) = 0 THEN 1
        ELSE 0
    END,
    CASE
        WHEN MOD(m.medication_id, 20) = 0 THEN DATE_SUB(@slot_start, INTERVAL 1 MINUTE)
        ELSE NULL
    END,
    'Medication reminder',
    CONCAT(m.drug_name, ' dose is due.'),
    JSON_OBJECT(
        'title', 'Medication reminder',
        'body', CONCAT(m.drug_name, ' dose is due.'),
        'medicationId', m.medication_id,
        'doseSequence', 1,
        'scheduledAt', DATE_FORMAT(@slot_start, '%Y-%m-%dT%H:%i:%s')
    )
FROM medication m
JOIN `user` u ON u.user_id = m.user_id
WHERE u.login_id LIKE 'batch_user_%'
ON DUPLICATE KEY UPDATE notification_send_id = notification_send_id;

-- 5. Seed stale PROCESSING rows for RetryJob recovery measurement.
UPDATE medication_notification_send
SET status = 'PROCESSING',
    attempt_count = CASE WHEN MOD(notification_send_id, 10) = 0 THEN 3 ELSE 1 END,
    claim_token = UUID(),
    claimed_at = DATE_SUB(@slot_start, INTERVAL 20 MINUTE),
    claimed_by = 'seed-worker',
    claim_expires_at = DATE_SUB(@slot_start, INTERVAL 10 MINUTE),
    last_attempted_at = DATE_SUB(@slot_start, INTERVAL 20 MINUTE)
WHERE MOD(notification_send_id, 50) = 0;

-- 6. Summary.
SELECT 'users' AS metric_name, COUNT(*) AS metric_value
FROM `user`
WHERE login_id LIKE 'batch_user_%'
UNION ALL
SELECT 'medications', COUNT(*)
FROM medication m
JOIN `user` u ON u.user_id = m.user_id
WHERE u.login_id LIKE 'batch_user_%'
UNION ALL
SELECT 'outbox_rows', COUNT(*)
FROM medication_notification_send;
