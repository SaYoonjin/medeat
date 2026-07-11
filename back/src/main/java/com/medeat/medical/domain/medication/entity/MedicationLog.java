package com.medeat.medical.domain.medication.entity;

import com.medeat.auth.domain.user.entity.User;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "medication_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_medication_log_med_date_index",
                columnNames = {"medication_id", "taken_date", "taken_index"}
        )
)
public class MedicationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medication_id", nullable = false)
    private Medication medication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "taken_index")
    private Integer takenIndex;

    @Column(name = "taken_date", nullable = false)
    private LocalDate takenDate;

    @Column(name = "taken_at", insertable = false, updatable = false)
    private LocalDateTime takenAt;

    @PrePersist
    void prePersist() {
        if (takenDate == null) {
            takenDate = LocalDate.now();
        }
    }

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public Medication getMedication() {
        return medication;
    }

    public void setMedication(Medication medication) {
        this.medication = medication;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getTakenIndex() {
        return takenIndex;
    }

    public void setTakenIndex(Integer takenIndex) {
        this.takenIndex = takenIndex;
    }

    public LocalDate getTakenDate() {
        return takenDate;
    }

    public void setTakenDate(LocalDate takenDate) {
        this.takenDate = takenDate;
    }

    public LocalDateTime getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(LocalDateTime takenAt) {
        this.takenAt = takenAt;
    }
}
