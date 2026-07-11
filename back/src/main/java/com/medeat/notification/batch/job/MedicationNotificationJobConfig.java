package com.medeat.notification.batch.job;

import com.medeat.notification.batch.prepare.PrepareNotificationTasklet;
import com.medeat.notification.batch.retry.StaleProcessingRecoveryTasklet;
import com.medeat.notification.batch.send.SendNotificationTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class MedicationNotificationJobConfig {

    public static final String MEDICATION_NOTIFICATION_JOB_NAME = "medicationNotificationJob";
    public static final String MEDICATION_NOTIFICATION_RETRY_JOB_NAME = "medicationNotificationRetryJob";
    public static final String PREPARE_NOTIFICATION_STEP_NAME = "prepareNotificationStep";
    public static final String SEND_NOTIFICATION_STEP_NAME = "sendNotificationStep";
    public static final String STALE_PROCESSING_RECOVERY_STEP_NAME = "staleProcessingRecoveryStep";

    @Bean
    public Job medicationNotificationJob(
            JobRepository jobRepository,
            Step prepareNotificationStep,
            Step sendNotificationStep
    ) {
        return new JobBuilder(MEDICATION_NOTIFICATION_JOB_NAME, jobRepository)
                .start(prepareNotificationStep)
                .next(sendNotificationStep)
                .build();
    }

    @Bean
    public Job medicationNotificationRetryJob(
            JobRepository jobRepository,
            Step staleProcessingRecoveryStep,
            Step sendNotificationStep
    ) {
        return new JobBuilder(MEDICATION_NOTIFICATION_RETRY_JOB_NAME, jobRepository)
                .start(staleProcessingRecoveryStep)
                .next(sendNotificationStep)
                .build();
    }

    @Bean
    public Step prepareNotificationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            PrepareNotificationTasklet prepareNotificationTasklet
    ) {
        return new StepBuilder(PREPARE_NOTIFICATION_STEP_NAME, jobRepository)
                .tasklet(prepareNotificationTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step sendNotificationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SendNotificationTasklet sendNotificationTasklet
    ) {
        return new StepBuilder(SEND_NOTIFICATION_STEP_NAME, jobRepository)
                .tasklet(sendNotificationTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step staleProcessingRecoveryStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            StaleProcessingRecoveryTasklet staleProcessingRecoveryTasklet
    ) {
        return new StepBuilder(STALE_PROCESSING_RECOVERY_STEP_NAME, jobRepository)
                .tasklet(staleProcessingRecoveryTasklet, transactionManager)
                .build();
    }
}
