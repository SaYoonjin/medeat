CREATE TABLE IF NOT EXISTS medication_schedule (
    medication_schedule_id BIGINT NOT NULL AUTO_INCREMENT,
    medication_id BIGINT NOT NULL,
    intake_time TIME NOT NULL,
    CONSTRAINT pk_medication_schedule PRIMARY KEY (medication_schedule_id),
    CONSTRAINT uk_medication_schedule_med_time UNIQUE (medication_id, intake_time),
    CONSTRAINT fk_medication_schedule_medication
        FOREIGN KEY (medication_id) REFERENCES medication (medication_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_medication_schedule_intake_time
    ON medication_schedule (intake_time, medication_id);

INSERT IGNORE INTO medication_schedule (
    medication_id,
    intake_time
)
SELECT
    m.medication_id,
    CAST(STR_TO_DATE(jt.token, '%H:%i') AS TIME)
FROM medication m
JOIN JSON_TABLE(
    CONCAT(
        '["',
        REPLACE(REPLACE(TRIM(COALESCE(m.intake_time, '')), ' ', ''), ',', '","'),
        '"]'
    ),
    '$[*]' COLUMNS (
        token VARCHAR(10) PATH '$'
    )
) jt
WHERE jt.token REGEXP '^([01]?[0-9]|2[0-3]):[0-5][0-9]$';
