package com.medeat.notification.batch.send;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SendNotificationTasklet implements Tasklet {

    public static final String CANDIDATE_COUNT = "send.candidateCount";
    public static final String CLAIMED_COUNT = "send.claimedCount";
    public static final String SENT_COUNT = "send.sentCount";
    public static final String RETRY_COUNT = "send.retryCount";
    public static final String FAILED_COUNT = "send.failedCount";
    public static final String SKIPPED_COUNT = "send.skippedCount";
    public static final String CLAIM_CONFLICT_COUNT = "send.claimConflictCount";
    public static final String STALE_RESULT_COUNT = "send.staleResultCount";

    private static final Logger log = LoggerFactory.getLogger(SendNotificationTasklet.class);

    private final MedicationNotificationSender sender;

    public SendNotificationTasklet(MedicationNotificationSender sender) {
        this.sender = sender;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SendNotificationResult result = sender.sendDueNotifications(LocalDateTime.now());

        contribution.getStepExecution().getExecutionContext().putInt(CANDIDATE_COUNT, result.candidateCount());
        contribution.getStepExecution().getExecutionContext().putInt(CLAIMED_COUNT, result.claimedCount());
        contribution.getStepExecution().getExecutionContext().putInt(SENT_COUNT, result.sentCount());
        contribution.getStepExecution().getExecutionContext().putInt(RETRY_COUNT, result.retryCount());
        contribution.getStepExecution().getExecutionContext().putInt(FAILED_COUNT, result.failedCount());
        contribution.getStepExecution().getExecutionContext().putInt(SKIPPED_COUNT, result.skippedCount());
        contribution.getStepExecution().getExecutionContext().putInt(
                CLAIM_CONFLICT_COUNT,
                result.claimConflictCount()
        );
        contribution.getStepExecution().getExecutionContext().putInt(STALE_RESULT_COUNT, result.staleResultCount());

        log.info(
                "Sent medication notifications. candidateCount={}, claimedCount={}, sentCount={}, retryCount={}, "
                        + "failedCount={}, skippedCount={}, claimConflictCount={}, staleResultCount={}",
                result.candidateCount(),
                result.claimedCount(),
                result.sentCount(),
                result.retryCount(),
                result.failedCount(),
                result.skippedCount(),
                result.claimConflictCount(),
                result.staleResultCount()
        );

        return RepeatStatus.FINISHED;
    }
}
