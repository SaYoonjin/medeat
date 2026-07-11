package com.medeat.notification.batch.prepare;

import com.medeat.notification.batch.job.MedicationNotificationJobParameterNames;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrepareNotificationTaskletTest {

    private final PrepareNotificationService prepareNotificationService = mock(PrepareNotificationService.class);
    private final PrepareNotificationTasklet tasklet = new PrepareNotificationTasklet(prepareNotificationService);

    @Test
    void executesPrepareServiceWithJobParametersAndStoresResultInStepContext() throws Exception {
        LocalDateTime slotStart = LocalDateTime.of(2026, 7, 12, 8, 0);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 7, 12, 8, 5);
        StepExecution stepExecution = stepExecution(slotStart, slotEnd);
        StepContribution contribution = new StepContribution(stepExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

        when(prepareNotificationService.prepare(slotStart, slotEnd, 100L))
                .thenReturn(new PrepareNotificationResult(10, 4, 4, 3, 1));

        RepeatStatus result = tasklet.execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(prepareNotificationService).prepare(slotStart, slotEnd, 100L);
        assertThat(stepExecution.getExecutionContext().getInt(PrepareNotificationTasklet.CANDIDATE_COUNT))
                .isEqualTo(10);
        assertThat(stepExecution.getExecutionContext().getInt(PrepareNotificationTasklet.DUE_DOSE_COUNT))
                .isEqualTo(4);
        assertThat(stepExecution.getExecutionContext().getInt(PrepareNotificationTasklet.INSERT_ATTEMPT_COUNT))
                .isEqualTo(4);
        assertThat(stepExecution.getExecutionContext().getInt(PrepareNotificationTasklet.CREATED_COUNT))
                .isEqualTo(3);
        assertThat(stepExecution.getExecutionContext().getInt(PrepareNotificationTasklet.DUPLICATE_COUNT))
                .isEqualTo(1);
    }

    private StepExecution stepExecution(LocalDateTime slotStart, LocalDateTime slotEnd) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(MedicationNotificationJobParameterNames.SLOT_START, slotStart.toString())
                .addString(MedicationNotificationJobParameterNames.SLOT_END, slotEnd.toString())
                .addString(MedicationNotificationJobParameterNames.ZONE_ID, "Asia/Seoul")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(100L, jobParameters);
        return new StepExecution("prepareNotificationStep", jobExecution, 200L);
    }
}
