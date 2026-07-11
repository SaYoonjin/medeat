package com.medeat.medical.domain.medication.query;

import com.medeat.medical.domain.medication.entity.QMedication;
import com.medeat.medical.domain.medication.entity.QMedicationLog;
import com.medeat.medical.dto.MedicationDto;
import com.medeat.medical.dto.MedicationLogDto;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class MedicationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public MedicationQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public List<MedicationLogDto> findTodayLogs(Long userId) {
        LocalDate today = LocalDate.now();
        return findLogsByPeriod(userId, today, today);
    }

    public List<MedicationLogDto> findTodayLogsByMedication(Long medicationId) {
        LocalDate today = LocalDate.now();
        QMedicationLog medicationLog = QMedicationLog.medicationLog;

        return queryFactory
                .select(Projections.bean(
                        MedicationLogDto.class,
                        medicationLog.logId.as("logId"),
                        medicationLog.medication().medicationId.as("medicationId"),
                        medicationLog.user().userId.as("userId"),
                        medicationLog.takenIndex.as("takenIndex"),
                        Expressions.stringTemplate("date_format({0}, {1})", medicationLog.takenDate, "%Y-%m-%d").as("takenDate"),
                        medicationLog.takenAt.as("takenAt")
                ))
                .from(medicationLog)
                .where(
                        medicationLog.medication().medicationId.eq(medicationId),
                        medicationLog.takenDate.eq(today)
                )
                .orderBy(medicationLog.takenIndex.asc())
                .fetch();
    }

    public List<MedicationLogDto> findLogsByPeriod(Long userId, LocalDate startDate, LocalDate endDate) {
        QMedicationLog medicationLog = QMedicationLog.medicationLog;

        return queryFactory
                .select(Projections.bean(
                        MedicationLogDto.class,
                        medicationLog.logId.as("logId"),
                        medicationLog.medication().medicationId.as("medicationId"),
                        medicationLog.user().userId.as("userId"),
                        medicationLog.takenIndex.as("takenIndex"),
                        Expressions.stringTemplate("date_format({0}, {1})", medicationLog.takenDate, "%Y-%m-%d").as("takenDate"),
                        medicationLog.takenAt.as("takenAt")
                ))
                .from(medicationLog)
                .where(
                        medicationLog.user().userId.eq(userId),
                        medicationLog.takenDate.between(startDate, endDate)
                )
                .orderBy(medicationLog.takenAt.asc())
                .fetch();
    }

    public List<MedicationDto> findMedicationToAlert(String nowTime) {
        QMedication medication = QMedication.medication;

        return queryFactory
                .select(Projections.bean(
                        MedicationDto.class,
                        medication.medicationId.as("medicationId"),
                        medication.user().userId.as("userId"),
                        medication.itemSeq.as("itemSeq"),
                        medication.drugName.as("drugName"),
                        medication.ingredient.as("ingredient"),
                        medication.dose.as("dose"),
                        medication.intakeTime.as("intakeTime"),
                        medication.intervalHour.as("intervalHour"),
                        medication.memo.as("memo"),
                        medication.dailyCount.as("dailyCount"),
                        medication.recommended.as("recommended")
                ))
                .from(medication)
                .where(Expressions.booleanTemplate(
                        "find_in_set({0}, replace({1}, ' ', '')) > 0",
                        nowTime,
                        medication.intakeTime
                ))
                .orderBy(medication.medicationId.asc())
                .fetch();
    }
}
