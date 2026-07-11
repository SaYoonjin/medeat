package com.medeat.notification.batch.prepare;

import com.medeat.notification.batch.job.MedicationNotificationJobParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
public class PrepareNotificationTasklet implements Tasklet {

    public static final String CANDIDATE_COUNT = "prepare.medicationCandidateCount";
    public static final String DUE_DOSE_COUNT = "prepare.dueDoseCount";
    public static final String INSERT_ATTEMPT_COUNT = "prepare.outboxInsertAttemptCount";
    public static final String CREATED_COUNT = "prepare.outboxCreatedCount";
    public static final String DUPLICATE_COUNT = "prepare.outboxDuplicateCount";

    private static final Logger log = LoggerFactory.getLogger(PrepareNotificationTasklet.class);

    private final PrepareNotificationService prepareNotificationService;

    public PrepareNotificationTasklet(PrepareNotificationService prepareNotificationService) {
        this.prepareNotificationService = prepareNotificationService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        MedicationNotificationJobParameters parameters = MedicationNotificationJobParameters.from(
                contribution.getStepExecution().getJobParameters()
        );
        Long jobExecutionId = contribution.getStepExecution().getJobExecution().getId();

        PrepareNotificationResult result = prepareNotificationService.prepare(
                parameters.slotStart(),
                parameters.slotEnd(),
                jobExecutionId
        );

        contribution.getStepExecution().getExecutionContext().putInt(CANDIDATE_COUNT, result.medicationCandidateCount());
        contribution.getStepExecution().getExecutionContext().putInt(DUE_DOSE_COUNT, result.dueDoseCount());
        contribution.getStepExecution().getExecutionContext().putInt(
                INSERT_ATTEMPT_COUNT,
                result.outboxInsertAttemptCount()
        );
        contribution.getStepExecution().getExecutionContext().putInt(CREATED_COUNT, result.outboxCreatedCount());
        contribution.getStepExecution().getExecutionContext().putInt(DUPLICATE_COUNT, result.outboxDuplicateCount());

        log.info(
                "Prepared medication notifications. slotStart={}, slotEnd={}, zoneId={}, candidateCount={}, "
                        + "dueDoseCount={}, insertAttemptCount={}, createdCount={}, duplicateCount={}",
                parameters.slotStart(),
                parameters.slotEnd(),
                parameters.zoneId(),
                result.medicationCandidateCount(),
                result.dueDoseCount(),
                result.outboxInsertAttemptCount(),
                result.outboxCreatedCount(),
                result.outboxDuplicateCount()
        );

        return RepeatStatus.FINISHED;
    }
}
