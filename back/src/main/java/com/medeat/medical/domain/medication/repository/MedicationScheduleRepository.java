package com.medeat.medical.domain.medication.repository;

import com.medeat.medical.domain.medication.entity.MedicationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationScheduleRepository extends JpaRepository<MedicationSchedule, Long> {

    void deleteByMedicationMedicationId(Long medicationId);
}
