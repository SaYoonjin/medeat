package com.medeat.notification.batch.scheduler;

import com.medeat.notification.batch.job.MedicationNotificationJobParameterNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(
        prefix = "medeat.notification.batch.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class MedicationNotificationBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedicationNotificationBatchScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job medicationNotificationJob;
    private final MedicationNotificationSlotCalculator slotCalculator;
    private final ZoneId zoneId;
    private final int slotMinutes;

    public MedicationNotificationBatchScheduler(
            JobLauncher jobLauncher,
            @Qualifier("medicationNotificationJob") Job medicationNotificationJob,
            MedicationNotificationSlotCalculator slotCalculator,
            @Value("${medeat.notification.batch.scheduler.zone-id:Asia/Seoul}") String zoneId,
            @Value("${medeat.notification.batch.scheduler.slot-minutes:5}") int slotMinutes
    ) {
        this.jobLauncher = jobLauncher;
        this.medicationNotificationJob = medicationNotificationJob;
        this.slotCalculator = slotCalculator;
        this.zoneId = ZoneId.of(zoneId);
        this.slotMinutes = slotMinutes;
    }

    @Scheduled(
            cron = "${medeat.notification.batch.scheduler.cron:0 */5 * * * *}",
            zone = "${medeat.notification.batch.scheduler.zone-id:Asia/Seoul}"
    )
    public void runMedicationNotificationJob() {
        launchFor(LocalDateTime.now(zoneId));
    }

    void launchFor(LocalDateTime now) {
        NotificationTimeSlot slot = slotCalculator.previousSlot(now, slotMinutes);
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(MedicationNotificationJobParameterNames.SLOT_START, slot.slotStart().toString())
                .addString(MedicationNotificationJobParameterNames.SLOT_END, slot.slotEnd().toString())
                .addString(MedicationNotificationJobParameterNames.ZONE_ID, zoneId.getId())
                .toJobParameters();

        try {
            JobExecution execution = jobLauncher.run(medicationNotificationJob, jobParameters);
            log.info(
                    "Launched medication notification batch. jobExecutionId={}, slotStart={}, slotEnd={}, zoneId={}",
                    execution.getId(),
                    slot.slotStart(),
                    slot.slotEnd(),
                    zoneId
            );
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info(
                    "Medication notification batch already completed for slot. slotStart={}, slotEnd={}, zoneId={}",
                    slot.slotStart(),
                    slot.slotEnd(),
                    zoneId
            );
        } catch (Exception e) {
            log.error(
                    "Failed to launch medication notification batch. slotStart={}, slotEnd={}, zoneId={}",
                    slot.slotStart(),
                    slot.slotEnd(),
                    zoneId,
                    e
            );
        }
    }
}
