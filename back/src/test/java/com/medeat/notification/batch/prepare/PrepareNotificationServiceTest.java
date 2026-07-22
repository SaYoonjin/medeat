package com.medeat.notification.batch.prepare;

import com.medeat.medical.domain.medication.entity.Medication;
import com.medeat.medical.domain.medication.mapper.MedicationMapper;
import com.medeat.medical.domain.medication.repository.MedicationRepository;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.notification.batch.outbox.MedicationNotificationOutboxCommand;
import com.medeat.notification.batch.outbox.MedicationNotificationOutboxFactory;
import com.medeat.notification.batch.outbox.MedicationNotificationSendDao;
import com.medeat.notification.batch.schedule.DoseScheduleCalculator;
import com.medeat.notification.batch.schedule.ScheduleSource;
import com.medeat.notification.batch.schedule.ScheduledDose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrepareNotificationServiceTest {

    private final MedicationRepository medicationRepository = mock(MedicationRepository.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final DoseScheduleCalculator doseScheduleCalculator = mock(DoseScheduleCalculator.class);
    private final MedicationNotificationOutboxFactory outboxFactory = mock(MedicationNotificationOutboxFactory.class);
    private final MedicationNotificationSendDao notificationSendDao = mock(MedicationNotificationSendDao.class);

    private final PrepareNotificationService service = new PrepareNotificationService(
            medicationRepository,
            medicationMapper,
            doseScheduleCalculator,
            outboxFactory,
            notificationSendDao
    );

    @Test
    void preparesDueDosesAsPendingOutboxRows() {
        LocalDateTime slotStart = LocalDateTime.of(2026, 7, 11, 8, 0);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 7, 11, 8, 5);
        Medication medication = new Medication();
        MedicationDto medicationDto = medicationDto();
        ScheduledDose dose = new ScheduledDose(
                10L,
                1L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                1,
                ScheduleSource.INTERVAL_CALCULATED
        );
        MedicationNotificationOutboxCommand command = command();

        when(medicationRepository.findAllByOrderByMedicationIdAsc()).thenReturn(List.of(medication));
        when(medicationMapper.toDto(medication)).thenReturn(medicationDto);
        when(doseScheduleCalculator.calculate(medicationDto, slotStart, slotEnd)).thenReturn(List.of(dose));
        when(outboxFactory.create(medicationDto, dose, 100L)).thenReturn(command);
        when(notificationSendDao.insertPendingBatch(List.of(command))).thenReturn(new int[] {1});
        when(notificationSendDao.countCreatedByOriginJobExecutionId(100L)).thenReturn(1);

        PrepareNotificationResult result = service.prepare(slotStart, slotEnd, 100L);

        assertThat(result.medicationCandidateCount()).isEqualTo(1);
        assertThat(result.dueDoseCount()).isEqualTo(1);
        assertThat(result.outboxInsertAttemptCount()).isEqualTo(1);
        assertThat(result.outboxCreatedCount()).isEqualTo(1);
        assertThat(result.outboxDuplicateCount()).isZero();

        ArgumentCaptor<List<MedicationNotificationOutboxCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationSendDao).insertPendingBatch(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).containsExactly(command);
    }

    @Test
    void doesNotWriteWhenNoDoseIsDueInSlot() {
        LocalDateTime slotStart = LocalDateTime.of(2026, 7, 11, 8, 0);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 7, 11, 8, 5);
        Medication medication = new Medication();
        MedicationDto medicationDto = medicationDto();

        when(medicationRepository.findAllByOrderByMedicationIdAsc()).thenReturn(List.of(medication));
        when(medicationMapper.toDto(medication)).thenReturn(medicationDto);
        when(doseScheduleCalculator.calculate(medicationDto, slotStart, slotEnd)).thenReturn(List.of());

        PrepareNotificationResult result = service.prepare(slotStart, slotEnd, 100L);

        assertThat(result.medicationCandidateCount()).isEqualTo(1);
        assertThat(result.dueDoseCount()).isZero();
        assertThat(result.outboxInsertAttemptCount()).isZero();
        assertThat(result.outboxCreatedCount()).isZero();
        assertThat(result.outboxDuplicateCount()).isZero();
        verify(notificationSendDao, never()).insertPendingBatch(anyList());
    }

    @Test
    void countsDuplicateOutboxRowsFromOriginJobExecutionId() {
        LocalDateTime slotStart = LocalDateTime.of(2026, 7, 11, 8, 0);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 7, 11, 8, 5);
        Medication medication = new Medication();
        MedicationDto medicationDto = medicationDto();
        ScheduledDose dose = new ScheduledDose(
                10L,
                1L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                1,
                ScheduleSource.INTERVAL_CALCULATED
        );
        MedicationNotificationOutboxCommand command = command();

        when(medicationRepository.findAllByOrderByMedicationIdAsc()).thenReturn(List.of(medication));
        when(medicationMapper.toDto(medication)).thenReturn(medicationDto);
        when(doseScheduleCalculator.calculate(medicationDto, slotStart, slotEnd)).thenReturn(List.of(dose));
        when(outboxFactory.create(medicationDto, dose, 100L)).thenReturn(command);
        when(notificationSendDao.insertPendingBatch(List.of(command))).thenReturn(new int[] {1});
        when(notificationSendDao.countCreatedByOriginJobExecutionId(100L)).thenReturn(0);

        PrepareNotificationResult result = service.prepare(slotStart, slotEnd, 100L);

        assertThat(result.outboxInsertAttemptCount()).isEqualTo(1);
        assertThat(result.outboxCreatedCount()).isZero();
        assertThat(result.outboxDuplicateCount()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidSlot() {
        LocalDateTime slotStart = LocalDateTime.of(2026, 7, 11, 8, 5);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 7, 11, 8, 0);

        assertThatThrownBy(() -> service.prepare(slotStart, slotEnd, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("slotStart must be before slotEnd");
    }

    private MedicationDto medicationDto() {
        MedicationDto medication = new MedicationDto();
        medication.setMedicationId(10L);
        medication.setUserId(1L);
        medication.setDrugName("Tylenol");
        medication.setIntakeTime("08:00");
        medication.setIntervalHour(8);
        medication.setDailyCount(3);
        return medication;
    }

    private MedicationNotificationOutboxCommand command() {
        return new MedicationNotificationOutboxCommand(
                1L,
                10L,
                LocalDateTime.of(2026, 7, 11, 8, 0),
                1,
                "Medication reminder",
                "Tylenol dose is due.",
                "{\"ok\":true}",
                100L
        );
    }
}
