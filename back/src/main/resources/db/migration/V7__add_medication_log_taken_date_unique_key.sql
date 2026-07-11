DELIMITER //

CREATE PROCEDURE migrate_medication_log_taken_date()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'medication_log'
          AND column_name = 'taken_date'
    ) THEN
        ALTER TABLE medication_log
            ADD COLUMN taken_date DATE NULL AFTER taken_index;
    END IF;

    UPDATE medication_log
    SET taken_date = DATE(taken_at)
    WHERE taken_date IS NULL;

    UPDATE medication_log
    SET taken_index = 0
    WHERE taken_index IS NULL;

    ALTER TABLE medication_log
        MODIFY taken_date DATE NOT NULL;

    ALTER TABLE medication_log
        MODIFY taken_index INT NOT NULL;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'medication_log'
          AND index_name = 'uk_medication_log_med_date_index'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM (
            SELECT medication_id, taken_date, taken_index
            FROM medication_log
            GROUP BY medication_id, taken_date, taken_index
            HAVING COUNT(*) > 1
            LIMIT 1
        ) duplicated_medication_log
    ) THEN
        ALTER TABLE medication_log
            ADD CONSTRAINT uk_medication_log_med_date_index
            UNIQUE (medication_id, taken_date, taken_index);
    END IF;
END//

CALL migrate_medication_log_taken_date()//

DROP PROCEDURE migrate_medication_log_taken_date//

DELIMITER ;
