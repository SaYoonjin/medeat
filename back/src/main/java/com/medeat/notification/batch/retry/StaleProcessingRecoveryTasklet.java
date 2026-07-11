package com.medeat.notification.batch.retry;

import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class StaleProcessingRecoveryTasklet implements Tasklet {

    public static final String RECOVERED_COUNT = "retry.staleProcessingRecoveredCount";

    private static final Logger log = LoggerFactory.getLogger(StaleProcessingRecoveryTasklet.class);

    private final MedicationNotificationSendDao notificationSendDao;
    private final int maxAttempts;
    private final int recoveryBackoffMinutes;

    public StaleProcessingRecoveryTasklet(
            MedicationNotificationSendDao notificationSendDao,
            @Value("${medeat.notification.batch.send.max-attempts:3}") int maxAttempts,
            @Value("${medeat.notification.batch.retry.recovery-backoff-minutes:1}") int recoveryBackoffMinutes
    ) {
        this.notificationSendDao = notificationSendDao;
        this.maxAttempts = maxAttempts;
        this.recoveryBackoffMinutes = recoveryBackoffMinutes;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime now = LocalDateTime.now();
        int recoveredCount = notificationSendDao.recoverStaleProcessing(
                now,
                maxAttempts,
                now.plusMinutes(recoveryBackoffMinutes)
        );

        contribution.getStepExecution().getExecutionContext().putInt(RECOVERED_COUNT, recoveredCount);
        log.info("Recovered stale PROCESSING medication notifications. recoveredCount={}", recoveredCount);
        return RepeatStatus.FINISHED;
    }
}
