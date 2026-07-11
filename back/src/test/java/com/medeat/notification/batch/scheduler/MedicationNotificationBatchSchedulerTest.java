package com.medeat.notification.batch.scheduler;

import com.medeat.notification.batch.job.MedicationNotificationJobParameterNames;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicationNotificationBatchSchedulerTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job job = mock(Job.class);
    private final MedicationNotificationSlotCalculator slotCalculator = new MedicationNotificationSlotCalculator();
    private final MedicationNotificationBatchScheduler scheduler = new MedicationNotificationBatchScheduler(
            jobLauncher,
            job,
            slotCalculator,
            "Asia/Seoul",
            5
    );

    @Test
    void launchesJobWithPreviousSlotParameters() throws Exception {
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobExecution.getId()).thenReturn(100L);
        when(jobLauncher.run(same(job), any(JobParameters.class))).thenReturn(jobExecution);

        scheduler.launchFor(LocalDateTime.of(2026, 7, 12, 8, 5, 0));

        org.mockito.ArgumentCaptor<JobParameters> paramsCaptor =
                org.mockito.ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(same(job), paramsCaptor.capture());

        JobParameters parameters = paramsCaptor.getValue();
        assertThat(parameters.getString(MedicationNotificationJobParameterNames.SLOT_START))
                .isEqualTo("2026-07-12T08:00");
        assertThat(parameters.getString(MedicationNotificationJobParameterNames.SLOT_END))
                .isEqualTo("2026-07-12T08:05");
        assertThat(parameters.getString(MedicationNotificationJobParameterNames.ZONE_ID))
                .isEqualTo("Asia/Seoul");
    }

    @Test
    void ignoresAlreadyCompletedSlot() throws Exception {
        when(jobLauncher.run(same(job), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

        scheduler.launchFor(LocalDateTime.of(2026, 7, 12, 8, 5, 0));

        verify(jobLauncher).run(same(job), any(JobParameters.class));
    }

    @Test
    void catchesLaunchFailureSoSchedulerThreadCanContinue() throws Exception {
        when(jobLauncher.run(same(job), any(JobParameters.class)))
                .thenThrow(new JobRestartException("restart failure"));

        scheduler.launchFor(LocalDateTime.of(2026, 7, 12, 8, 5, 0));

        verify(jobLauncher).run(same(job), any(JobParameters.class));
    }
}
