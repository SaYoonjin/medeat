package com.medeat.notification.batch.job;

import org.springframework.batch.core.JobParameters;

import java.time.LocalDateTime;
import java.time.ZoneId;

public record MedicationNotificationJobParameters(
        LocalDateTime slotStart,
        LocalDateTime slotEnd,
        ZoneId zoneId
) {

    public static MedicationNotificationJobParameters from(JobParameters jobParameters) {
        String slotStart = required(jobParameters, MedicationNotificationJobParameterNames.SLOT_START);
        String slotEnd = required(jobParameters, MedicationNotificationJobParameterNames.SLOT_END);
        String zoneId = required(jobParameters, MedicationNotificationJobParameterNames.ZONE_ID);

        LocalDateTime parsedSlotStart = LocalDateTime.parse(slotStart);
        LocalDateTime parsedSlotEnd = LocalDateTime.parse(slotEnd);
        if (!parsedSlotStart.isBefore(parsedSlotEnd)) {
            throw new IllegalArgumentException("slotStart must be before slotEnd");
        }

        return new MedicationNotificationJobParameters(
                parsedSlotStart,
                parsedSlotEnd,
                ZoneId.of(zoneId)
        );
    }

    private static String required(JobParameters jobParameters, String name) {
        String value = jobParameters.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required JobParameter: " + name);
        }
        return value;
    }
}
