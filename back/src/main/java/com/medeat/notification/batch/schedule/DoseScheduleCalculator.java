package com.medeat.notification.batch.schedule;

import com.medeat.medical.dto.MedicationDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class DoseScheduleCalculator {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public List<ScheduledDose> calculate(
            MedicationDto medication,
            LocalDateTime slotStart,
            LocalDateTime slotEnd
    ) {
        if (medication == null || slotStart == null || slotEnd == null || !slotStart.isBefore(slotEnd)) {
            return List.of();
        }

        List<LocalTime> intakeTimes = parseIntakeTimes(medication.getIntakeTime());
        if (intakeTimes.size() >= 2) {
            return calculateFromExplicitTimes(medication, intakeTimes, slotStart, slotEnd);
        }

        if (intakeTimes.size() == 1 && isValidIntervalPolicy(medication)) {
            return calculateFromInterval(medication, intakeTimes.get(0), slotStart, slotEnd);
        }

        return List.of();
    }

    private List<ScheduledDose> calculateFromExplicitTimes(
            MedicationDto medication,
            List<LocalTime> intakeTimes,
            LocalDateTime slotStart,
            LocalDateTime slotEnd
    ) {
        List<ScheduledDose> doses = new ArrayList<>();
        for (LocalDate date : candidateDates(slotStart, slotEnd, false)) {
            for (int index = 0; index < intakeTimes.size(); index++) {
                LocalDateTime scheduledAt = date.atTime(intakeTimes.get(index));
                if (isInSlot(scheduledAt, slotStart, slotEnd)) {
                    doses.add(toScheduledDose(
                            medication,
                            scheduledAt,
                            index + 1,
                            ScheduleSource.LEGACY_EXPLICIT_TIMES
                    ));
                }
            }
        }
        return sort(doses);
    }

    private List<ScheduledDose> calculateFromInterval(
            MedicationDto medication,
            LocalTime firstDoseTime,
            LocalDateTime slotStart,
            LocalDateTime slotEnd
    ) {
        List<ScheduledDose> doses = new ArrayList<>();
        for (LocalDate baseDate : candidateDates(slotStart, slotEnd, true)) {
            LocalDateTime firstDoseAt = baseDate.atTime(firstDoseTime);
            for (int sequence = 1; sequence <= medication.getDailyCount(); sequence++) {
                LocalDateTime scheduledAt = firstDoseAt.plusHours(
                        (long) medication.getIntervalHour() * (sequence - 1)
                );
                if (isInSlot(scheduledAt, slotStart, slotEnd)) {
                    doses.add(toScheduledDose(
                            medication,
                            scheduledAt,
                            sequence,
                            ScheduleSource.INTERVAL_CALCULATED
                    ));
                }
            }
        }
        return sort(doses);
    }

    private List<LocalTime> parseIntakeTimes(String intakeTime) {
        if (intakeTime == null || intakeTime.isBlank()) {
            return List.of();
        }

        List<LocalTime> times = new ArrayList<>();
        for (String token : intakeTime.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                times.add(LocalTime.parse(value, TIME_FORMATTER));
            } catch (DateTimeParseException ignored) {
                // Invalid tokens make the schedule ineligible unless another valid policy remains.
            }
        }
        return times;
    }

    private boolean isValidIntervalPolicy(MedicationDto medication) {
        return medication.getIntervalHour() != null
                && medication.getIntervalHour() > 0
                && medication.getDailyCount() > 0;
    }

    private List<LocalDate> candidateDates(LocalDateTime slotStart, LocalDateTime slotEnd, boolean includePreviousDay) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (includePreviousDay) {
            dates.add(slotStart.toLocalDate().minusDays(1));
        }
        dates.add(slotStart.toLocalDate());
        dates.add(slotEnd.toLocalDate());
        return List.copyOf(dates);
    }

    private boolean isInSlot(LocalDateTime scheduledAt, LocalDateTime slotStart, LocalDateTime slotEnd) {
        return !scheduledAt.isBefore(slotStart) && scheduledAt.isBefore(slotEnd);
    }

    private ScheduledDose toScheduledDose(
            MedicationDto medication,
            LocalDateTime scheduledAt,
            int doseSequence,
            ScheduleSource source
    ) {
        return new ScheduledDose(
                medication.getMedicationId(),
                medication.getUserId(),
                scheduledAt,
                doseSequence,
                source
        );
    }

    private List<ScheduledDose> sort(List<ScheduledDose> doses) {
        return doses.stream()
                .sorted(Comparator
                        .comparing(ScheduledDose::scheduledAt)
                        .thenComparing(ScheduledDose::doseSequence))
                .toList();
    }
}
