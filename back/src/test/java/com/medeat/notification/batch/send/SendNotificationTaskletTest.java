package com.medeat.notification.batch.send;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SendNotificationTaskletTest {

    private final MedicationNotificationSender sender = mock(MedicationNotificationSender.class);
    private final SendNotificationTasklet tasklet = new SendNotificationTasklet(sender);

    @Test
    void storesSendResultInStepExecutionContext() throws Exception {
        StepExecution stepExecution = new StepExecution(
                "sendNotificationStep",
                new JobExecution(100L, new JobParameters()),
                200L
        );
        StepContribution contribution = new StepContribution(stepExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        when(sender.sendDueNotifications(any(LocalDateTime.class)))
                .thenReturn(new SendNotificationResult(10, 8, 3, 2, 1, 1, 2, 1));

        RepeatStatus result = tasklet.execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(sender).sendDueNotifications(any(LocalDateTime.class));
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.CANDIDATE_COUNT)).isEqualTo(10);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.CLAIMED_COUNT)).isEqualTo(8);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.SENT_COUNT)).isEqualTo(3);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.RETRY_COUNT)).isEqualTo(2);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.FAILED_COUNT)).isEqualTo(1);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.SKIPPED_COUNT)).isEqualTo(1);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.CLAIM_CONFLICT_COUNT))
                .isEqualTo(2);
        assertThat(stepExecution.getExecutionContext().getInt(SendNotificationTasklet.STALE_RESULT_COUNT))
                .isEqualTo(1);
    }
}
