package com.medeat.notification.batch.retry;

import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleProcessingRecoveryTaskletTest {

    private final MedicationNotificationSendDao notificationSendDao = mock(MedicationNotificationSendDao.class);
    private final StaleProcessingRecoveryTasklet tasklet =
            new StaleProcessingRecoveryTasklet(notificationSendDao, 3, 1);

    @Test
    void recoversStaleProcessingRowsAndStoresCount() throws Exception {
        StepExecution stepExecution = new StepExecution(
                "staleProcessingRecoveryStep",
                new JobExecution(100L, new JobParameters()),
                200L
        );
        StepContribution contribution = new StepContribution(stepExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        when(notificationSendDao.recoverStaleProcessing(any(LocalDateTime.class), eq(3), any(LocalDateTime.class)))
                .thenReturn(2);

        RepeatStatus result = tasklet.execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(notificationSendDao).recoverStaleProcessing(
                any(LocalDateTime.class),
                eq(3),
                any(LocalDateTime.class)
        );
        assertThat(stepExecution.getExecutionContext().getInt(StaleProcessingRecoveryTasklet.RECOVERED_COUNT))
                .isEqualTo(2);
    }
}
