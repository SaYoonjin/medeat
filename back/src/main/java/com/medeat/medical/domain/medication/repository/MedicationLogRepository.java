package com.medeat.medical.domain.medication.repository;

import com.medeat.medical.domain.medication.entity.MedicationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface MedicationLogRepository extends JpaRepository<MedicationLog, Long> {

    List<MedicationLog> findByUserUserIdAndTakenAtBetweenOrderByTakenAtAsc(
            Long userId,
            LocalDateTime start,
            LocalDateTime end
    );

    boolean existsByMedicationMedicationIdAndTakenDateAndTakenIndex(
            Long medicationId,
            LocalDate takenDate,
            Integer takenIndex
    );

    List<MedicationLog> findByMedicationMedicationIdAndTakenAtBetweenOrderByTakenIndexAsc(
            Long medicationId,
            LocalDateTime start,
            LocalDateTime end
    );
}
