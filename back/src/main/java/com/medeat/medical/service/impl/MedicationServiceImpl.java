package com.medeat.medical.service.impl;

import com.medeat.auth.domain.user.entity.User;
import com.medeat.auth.domain.user.repository.UserRepository;
import com.medeat.medical.domain.medication.entity.Medication;
import com.medeat.medical.domain.medication.entity.MedicationLog;
import com.medeat.medical.domain.medication.entity.MedicationSchedule;
import com.medeat.medical.domain.medication.mapper.MedicationMapper;
import com.medeat.medical.domain.medication.query.MedicationQueryRepository;
import com.medeat.medical.domain.medication.repository.MedicationLogRepository;
import com.medeat.medical.domain.medication.repository.MedicationRepository;
import com.medeat.medical.domain.medication.repository.MedicationScheduleRepository;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import com.medeat.medical.service.MedicationService;
import com.medeat.notification.feed.dto.NotificationFeedType;
import com.medeat.notification.feed.service.NotificationFeedService;
import com.medeat.notification.service.PushSubscriptionService;
import com.medeat.notification.service.WebPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MedicationServiceImpl implements MedicationService {

    private static final Logger log = LoggerFactory.getLogger(MedicationServiceImpl.class);
    private static final DateTimeFormatter INTAKE_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final MedicationRepository medicationRepository;
    private final MedicationLogRepository medicationLogRepository;
    private final MedicationScheduleRepository medicationScheduleRepository;
    private final UserRepository userRepository;
    private final MedicationMapper medicationMapper;
    private final MedicationQueryRepository medicationQueryRepository;
    private final WebPushService webPushService;
    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationFeedService notificationFeedService;

    public MedicationServiceImpl(
            MedicationRepository medicationRepository,
            MedicationLogRepository medicationLogRepository,
            MedicationScheduleRepository medicationScheduleRepository,
            UserRepository userRepository,
            MedicationMapper medicationMapper,
            MedicationQueryRepository medicationQueryRepository,
            WebPushService webPushService,
            PushSubscriptionService pushSubscriptionService,
            NotificationFeedService notificationFeedService
    ) {
        this.medicationRepository = medicationRepository;
        this.medicationLogRepository = medicationLogRepository;
        this.medicationScheduleRepository = medicationScheduleRepository;
        this.userRepository = userRepository;
        this.medicationMapper = medicationMapper;
        this.medicationQueryRepository = medicationQueryRepository;
        this.webPushService = webPushService;
        this.pushSubscriptionService = pushSubscriptionService;
        this.notificationFeedService = notificationFeedService;
    }

    @Override
    public List<MedicationDto> getMedicationList(Long userId) {
        return medicationRepository.findByUserUserIdOrderByMedicationIdDesc(userId)
                .stream()
                .map(medicationMapper::toDto)
                .toList();
    }

    @Override
    public MedicationDto getMedication(Long medicationId) {
        return medicationRepository.findById(medicationId)
                .map(medicationMapper::toDto)
                .orElse(null);
    }

    @Override
    @Transactional
    public void addMedication(MedicationDto dto) {
        if (existsMedication(dto.getUserId(), dto.getDrugName())) {
            throw new IllegalArgumentException("이미 등록된 약입니다.");
        }

        User user = getRequiredUser(dto.getUserId());
        Medication saved = medicationRepository.save(medicationMapper.toEntity(dto, user));
        syncMedicationSchedules(saved, dto.getIntakeTime());
    }

    @Override
    @Transactional
    public void updateMedication(MedicationDto dto) {
        if (medicationRepository.existsByUserUserIdAndDrugNameAndMedicationIdNot(
                dto.getUserId(), dto.getDrugName(), dto.getMedicationId())) {
            throw new IllegalArgumentException("이미 같은 이름의 약이 존재합니다.");
        }

        Medication entity = medicationRepository.findById(dto.getMedicationId())
                .orElseThrow(() -> new IllegalArgumentException("복약 정보를 찾을 수 없습니다."));

        medicationMapper.apply(dto, entity);
        syncMedicationSchedules(entity, dto.getIntakeTime());
        notificationFeedService.notify(
                dto.getUserId(),
                NotificationFeedType.MEDICATION_SETTING_CHANGED,
                "DISEASE",
                dto.getUserId()
        );
    }

    @Override
    @Transactional
    public void deleteMedication(Long medicationId) {
        medicationRepository.deleteById(medicationId);
    }

    @Override
    public List<MedicationLogDto> getTodayLogs(Long userId) {
        return medicationQueryRepository.findTodayLogs(userId);
    }

    @Override
    @Transactional
    public void saveLog(Long userId, Long medicationId, int takenIndex) {
        LocalDate today = LocalDate.now();
        if (medicationLogRepository.existsByMedicationMedicationIdAndTakenDateAndTakenIndex(
                medicationId, today, takenIndex)) {
            return;
        }

        Medication medication = medicationRepository.findById(medicationId)
                .orElseThrow(() -> new IllegalArgumentException("복약 정보를 찾을 수 없습니다."));

        MedicationLog logEntity = new MedicationLog();
        logEntity.setUser(getRequiredUser(userId));
        logEntity.setMedication(medication);
        logEntity.setTakenIndex(takenIndex);
        logEntity.setTakenDate(today);
        medicationLogRepository.save(logEntity);
    }

    @Override
    public void checkAndSendMedicationAlarms() {
        String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<MedicationDto> medications = getMedicationToAlert(nowTime);

        for (MedicationDto medication : medications) {
            if (!pushSubscriptionService.isPushEnabled(medication.getUserId())) {
                continue;
            }

            try {
                webPushService.sendMedicationNotification(
                        medication.getUserId(),
                        medication.getMedicationId(),
                        getNextDoseIndex(medication),
                        "약 복용 알림",
                        medication.getDrugName() + " 복용 시간입니다."
                );
            } catch (Exception e) {
                log.warn("Failed to send medication notification. medicationId={}, userId={}",
                        medication.getMedicationId(), medication.getUserId(), e);
            }
        }
    }

    @Override
    public List<MedicationDto> getMedicationToAlert(String nowTime) {
        return medicationQueryRepository.findMedicationToAlert(nowTime);
    }

    @Override
    public int getNextDoseIndex(MedicationDto med) {
        return medicationQueryRepository.findTodayLogsByMedication(med.getMedicationId()).size() + 1;
    }

    @Override
    public boolean existsMedication(Long userId, String drugName) {
        return medicationRepository.existsByUserUserIdAndDrugName(userId, drugName);
    }

    @Override
    public List<MedicationLogDto> getLogs(Long userId, String startDate, String endDate) {
        return medicationQueryRepository.findLogsByPeriod(
                userId,
                LocalDate.parse(startDate),
                LocalDate.parse(endDate)
        );
    }

    private User getRequiredUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private void syncMedicationSchedules(Medication medication, String intakeTime) {
        if (medication == null || medication.getMedicationId() == null) {
            return;
        }

        medicationScheduleRepository.deleteByMedicationMedicationId(medication.getMedicationId());

        List<LocalTime> intakeTimes = parseIntakeTimes(intakeTime);
        if (intakeTimes.isEmpty()) {
            return;
        }

        List<MedicationSchedule> schedules = new ArrayList<>();
        for (LocalTime time : intakeTimes) {
            schedules.add(new MedicationSchedule(medication, time));
        }
        medicationScheduleRepository.saveAll(schedules);
    }

    private List<LocalTime> parseIntakeTimes(String intakeTime) {
        if (intakeTime == null || intakeTime.isBlank()) {
            return List.of();
        }

        Set<LocalTime> uniqueTimes = new LinkedHashSet<>();
        for (String token : intakeTime.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                uniqueTimes.add(LocalTime.parse(value, INTAKE_TIME_FORMATTER));
            } catch (DateTimeParseException ignored) {
                log.debug("Ignoring invalid medication intake time. value={}", value);
            }
        }
        return List.copyOf(uniqueTimes);
    }
}
