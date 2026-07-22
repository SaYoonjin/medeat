package com.medeat.medical.domain.medication.entity;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(
        name = "medication_schedule",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_medication_schedule_med_time",
                columnNames = {"medication_id", "intake_time"}
        ),
        indexes = @Index(
                name = "idx_medication_schedule_intake_time",
                columnList = "intake_time, medication_id"
        )
)
public class MedicationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "medication_schedule_id")
    private Long medicationScheduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medication_id", nullable = false)
    private Medication medication;

    @Column(name = "intake_time", nullable = false)
    private LocalTime intakeTime;

    public MedicationSchedule() {
    }

    public MedicationSchedule(Medication medication, LocalTime intakeTime) {
        this.medication = medication;
        this.intakeTime = intakeTime;
    }

    public Long getMedicationScheduleId() {
        return medicationScheduleId;
    }

    public void setMedicationScheduleId(Long medicationScheduleId) {
        this.medicationScheduleId = medicationScheduleId;
    }

    public Medication getMedication() {
        return medication;
    }

    public void setMedication(Medication medication) {
        this.medication = medication;
    }

    public LocalTime getIntakeTime() {
        return intakeTime;
    }

    public void setIntakeTime(LocalTime intakeTime) {
        this.intakeTime = intakeTime;
    }
}
