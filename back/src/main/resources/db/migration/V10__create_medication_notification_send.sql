CREATE TABLE IF NOT EXISTS medication_notification_send (
    notification_send_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    medication_id BIGINT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    dose_sequence INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    claim_token VARCHAR(36) NULL,
    claimed_at DATETIME(6) NULL,
    claimed_by VARCHAR(100) NULL,
    claim_expires_at DATETIME(6) NULL,
    notification_title VARCHAR(200) NOT NULL,
    notification_body VARCHAR(1000) NOT NULL,
    payload_json JSON NULL,
    last_attempted_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    failure_code VARCHAR(50) NULL,
    failure_reason VARCHAR(500) NULL,
    origin_job_execution_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_medication_notification_send PRIMARY KEY (notification_send_id),
    CONSTRAINT uk_medication_notification_dose
        UNIQUE (user_id, medication_id, scheduled_at, dose_sequence),
    CONSTRAINT fk_medication_notification_user
        FOREIGN KEY (user_id) REFERENCES `user` (user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_medication_notification_medication
        FOREIGN KEY (medication_id) REFERENCES medication (medication_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_med_notification_due_status
    ON medication_notification_send (
        status,
        next_retry_at,
        scheduled_at,
        notification_send_id
    );

CREATE INDEX idx_med_notification_schedule_status
    ON medication_notification_send (
        scheduled_at,
        status,
        attempt_count,
        notification_send_id
    );

CREATE INDEX idx_med_notification_claim_lease
    ON medication_notification_send (
        status,
        claim_expires_at,
        notification_send_id
    );
