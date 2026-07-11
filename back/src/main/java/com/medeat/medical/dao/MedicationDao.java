package com.medeat.medical.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;

public interface MedicationDao {

    List<MedicationDto> selectByUser(Long userId);

    MedicationDto selectById(Long medicationId);

    int insertMedication(MedicationDto dto);

    int updateMedication(MedicationDto dto);

    int deleteMedication(Long medicationId);
    
    // 오늘 복용한 약 기록 조회
    List<MedicationLogDto> getTodayLogs(Long userId);

    // 복용 기록 저장
    void insertLog(
        @Param("userId") Long userId,
        @Param("medicationId") Long medicationId,
        @Param("takenIndex") int takenIndex
    );
    
    // 전체 약 목록 (알림 스케줄러용)
    List<MedicationDto> selectAll();

    // 오늘 특정 약의 복용 기록
    List<MedicationLogDto> getTodayLogsByMedication(Long medicationId);
    
    int existsByUserAndName(Long userId, String drugName);
    
    int existsByUserAndNameExcludingId(Long userId, String drugName, Long medicationId);

 // 🔥 PDF / 리포트용 기간 조회
    List<MedicationLogDto> selectLogsByPeriod(
        @Param("userId") Long userId,
        @Param("startDate") String startDate,
        @Param("endDate") String endDate
    );

}
