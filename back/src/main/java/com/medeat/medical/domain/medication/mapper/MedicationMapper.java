package com.medeat.medical.domain.medication.mapper;

import com.medeat.auth.domain.user.entity.User;
import com.medeat.medical.domain.medication.entity.Medication;
import com.medeat.medical.domain.medication.entity.MedicationLog;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import org.springframework.stereotype.Component;

@Component
public class MedicationMapper {

    public MedicationDto toDto(Medication entity) {
        if (entity == null) {
            return null;
        }

        MedicationDto dto = new MedicationDto();
        dto.setMedicationId(entity.getMedicationId());
        dto.setUserId(entity.getUser().getUserId());
        dto.setItemSeq(entity.getItemSeq());
        dto.setDrugName(entity.getDrugName());
        dto.setIngredient(entity.getIngredient());
        dto.setDose(entity.getDose());
        dto.setIntakeTime(entity.getIntakeTime());
        dto.setIntervalHour(entity.getIntervalHour());
        dto.setMemo(entity.getMemo());
        dto.setDailyCount(entity.getDailyCount() == null ? 0 : entity.getDailyCount());
        dto.setRecommended(entity.getRecommended());
        return dto;
    }

    public MedicationLogDto toDto(MedicationLog entity) {
        MedicationLogDto dto = new MedicationLogDto();
        dto.setLogId(entity.getLogId());
        dto.setMedicationId(entity.getMedication().getMedicationId());
        dto.setUserId(entity.getUser().getUserId());
        dto.setTakenIndex(entity.getTakenIndex() == null ? 0 : entity.getTakenIndex());
        dto.setTakenAt(entity.getTakenAt());
        dto.setTakenDate(entity.getTakenDate() == null ? null : entity.getTakenDate().toString());
        return dto;
    }

    public Medication toEntity(MedicationDto dto, User user) {
        Medication entity = new Medication();
        entity.setUser(user);
        apply(dto, entity);
        return entity;
    }

    public void apply(MedicationDto dto, Medication entity) {
        entity.setItemSeq(dto.getItemSeq());
        entity.setDrugName(dto.getDrugName());
        entity.setIngredient(dto.getIngredient());
        entity.setDose(dto.getDose());
        entity.setIntakeTime(dto.getIntakeTime());
        entity.setIntervalHour(dto.getIntervalHour());
        entity.setMemo(dto.getMemo());
        entity.setDailyCount(dto.getDailyCount());
        entity.setRecommended(dto.getRecommended());
    }
}
