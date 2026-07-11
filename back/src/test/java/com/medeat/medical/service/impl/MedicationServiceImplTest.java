package com.medeat.medical.service.impl;

import com.medeat.auth.domain.user.entity.User;
import com.medeat.auth.domain.user.repository.UserRepository;
import com.medeat.medical.domain.medication.entity.Medication;
import com.medeat.medical.domain.medication.entity.MedicationLog;
import com.medeat.medical.domain.medication.mapper.MedicationMapper;
import com.medeat.medical.domain.medication.query.MedicationQueryRepository;
import com.medeat.medical.domain.medication.repository.MedicationLogRepository;
import com.medeat.medical.domain.medication.repository.MedicationRepository;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import com.medeat.notification.feed.service.NotificationFeedService;
import com.medeat.notification.service.PushSubscriptionService;
import com.medeat.notification.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationServiceImplTest {

    @Mock
    private MedicationRepository medicationRepository;

    @Mock
    private MedicationLogRepository medicationLogRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MedicationMapper medicationMapper;

    @Mock
    private MedicationQueryRepository medicationQueryRepository;

    @Mock
    private WebPushService webPushService;

    @Mock
    private PushSubscriptionService pushSubscriptionService;

    @Mock
    private NotificationFeedService notificationFeedService;

    @InjectMocks
    private MedicationServiceImpl medicationService;

    private MedicationDto medicationDto;

    @BeforeEach
    void setUp() {
        medicationDto = new MedicationDto();
        medicationDto.setMedicationId(10L);
        medicationDto.setUserId(1L);
        medicationDto.setDrugName("drug");
    }

    @Test
    void getTodayLogs_delegatesToQueryRepository() {
        MedicationLogDto logDto = new MedicationLogDto();
        when(medicationQueryRepository.findTodayLogs(1L)).thenReturn(List.of(logDto));

        List<MedicationLogDto> result = medicationService.getTodayLogs(1L);

        assertThat(result).containsExactly(logDto);
    }

    @Test
    void addMedication_savesMappedEntityWhenDuplicateDoesNotExist() {
        User user = new User();
        Medication entity = new Medication();

        when(medicationRepository.existsByUserUserIdAndDrugName(1L, "drug")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(medicationMapper.toEntity(medicationDto, user)).thenReturn(entity);

        medicationService.addMedication(medicationDto);

        verify(medicationRepository).save(entity);
    }

    @Test
    void saveLog_createsMedicationLogEntity() {
        User user = new User();
        user.setUserId(1L);
        Medication medication = new Medication();
        medication.setMedicationId(10L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(medicationRepository.findById(10L)).thenReturn(Optional.of(medication));

        medicationService.saveLog(1L, 10L, 2);

        ArgumentCaptor<MedicationLog> captor = ArgumentCaptor.forClass(MedicationLog.class);
        verify(medicationLogRepository).save(captor.capture());
        MedicationLog saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getMedication()).isSameAs(medication);
        assertThat(saved.getTakenIndex()).isEqualTo(2);
        assertThat(saved.getTakenDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void saveLog_skipsWhenSameDoseAlreadyExistsToday() {
        when(medicationLogRepository.existsByMedicationMedicationIdAndTakenDateAndTakenIndex(
                eq(10L), any(LocalDate.class), eq(2))).thenReturn(true);

        medicationService.saveLog(1L, 10L, 2);

        verify(medicationRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(medicationLogRepository, never()).save(any());
    }

    @Test
    void getMedicationToAlert_usesQueryRepository() {
        when(medicationQueryRepository.findMedicationToAlert("08:00")).thenReturn(List.of(medicationDto));

        List<MedicationDto> result = medicationService.getMedicationToAlert("08:00");

        assertThat(result).containsExactly(medicationDto);
    }

    @Test
    void updateMedication_appliesChangesToLoadedEntity() {
        Medication entity = new Medication();
        when(medicationRepository.existsByUserUserIdAndDrugNameAndMedicationIdNot(1L, "drug", 10L)).thenReturn(false);
        when(medicationRepository.findById(10L)).thenReturn(Optional.of(entity));

        medicationService.updateMedication(medicationDto);

        verify(medicationMapper).apply(medicationDto, entity);
        verify(notificationFeedService).notify(eq(1L), any(), eq("DISEASE"), eq(1L));
    }
}
