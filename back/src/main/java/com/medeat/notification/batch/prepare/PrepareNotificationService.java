package com.medeat.notification.batch.prepare;

import com.medeat.medical.domain.medication.entity.Medication;
import com.medeat.medical.domain.medication.mapper.MedicationMapper;
import com.medeat.medical.domain.medication.repository.MedicationRepository;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.notification.batch.outbox.MedicationNotificationOutboxCommand;
import com.medeat.notification.batch.outbox.MedicationNotificationOutboxFactory;
import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
import com.medeat.notification.batch.schedule.DoseScheduleCalculator;
import com.medeat.notification.batch.schedule.ScheduledDose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class PrepareNotificationService {

    private final MedicationRepository medicationRepository;
    private final MedicationMapper medicationMapper;
    private final DoseScheduleCalculator doseScheduleCalculator;
    private final MedicationNotificationOutboxFactory outboxFactory;
    private final MedicationNotificationSendDao notificationSendDao;

    public PrepareNotificationService(
            MedicationRepository medicationRepository,
            MedicationMapper medicationMapper,
            DoseScheduleCalculator doseScheduleCalculator,
            MedicationNotificationOutboxFactory outboxFactory,
            MedicationNotificationSendDao notificationSendDao
    ) {
        this.medicationRepository = medicationRepository;
        this.medicationMapper = medicationMapper;
        this.doseScheduleCalculator = doseScheduleCalculator;
        this.outboxFactory = outboxFactory;
        this.notificationSendDao = notificationSendDao;
    }

    @Transactional
    public PrepareNotificationResult prepare(
            LocalDateTime slotStart,
            LocalDateTime slotEnd,
            Long originJobExecutionId
    ) {
        validateSlot(slotStart, slotEnd);

        List<Medication> medications = medicationRepository.findAllByOrderByMedicationIdAsc();
        List<MedicationNotificationOutboxCommand> commands = new ArrayList<>();

        for (Medication medication : medications) {
            MedicationDto medicationDto = medicationMapper.toDto(medication);
            if (medicationDto == null) {
                continue;
            }

            List<ScheduledDose> doses = doseScheduleCalculator.calculate(medicationDto, slotStart, slotEnd);
            for (ScheduledDose dose : doses) {
                commands.add(outboxFactory.create(medicationDto, dose, originJobExecutionId));
            }
        }

        int createdCount = 0;
        if (!commands.isEmpty()) {
            int[] results = notificationSendDao.insertPendingBatch(commands);
            for (int result : results) {
                if (result > 0) {
                    createdCount++;
                }
            }
        }

        int insertAttemptCount = commands.size();
        return new PrepareNotificationResult(
                medications.size(),
                commands.size(),
                insertAttemptCount,
                createdCount,
                insertAttemptCount - createdCount
        );
    }

    private void validateSlot(LocalDateTime slotStart, LocalDateTime slotEnd) {
        Objects.requireNonNull(slotStart, "slotStart must not be null");
        Objects.requireNonNull(slotEnd, "slotEnd must not be null");
        if (!slotStart.isBefore(slotEnd)) {
            throw new IllegalArgumentException("slotStart must be before slotEnd");
        }
    }
}
