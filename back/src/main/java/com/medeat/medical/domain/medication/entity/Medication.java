package com.medeat.medical.domain.medication.entity;

import com.medeat.auth.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "medication")
public class Medication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "medication_id")
    private Long medicationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "item_seq")
    private Long itemSeq;

    @Column(name = "drug_name", nullable = false)
    private String drugName;

    private String ingredient;
    private String dose;

    @Column(name = "intake_time")
    private String intakeTime;

    @Column(name = "interval_hour")
    private Integer intervalHour;

    private String memo;

    @Column(name = "daily_count")
    private Integer dailyCount;

    private String recommended;

    @OneToMany(mappedBy = "medication", fetch = FetchType.LAZY)
    private List<MedicationLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "medication", fetch = FetchType.LAZY)
    private List<MedicationSchedule> schedules = new ArrayList<>();

    public Long getMedicationId() {
        return medicationId;
    }

    public void setMedicationId(Long medicationId) {
        this.medicationId = medicationId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Long getItemSeq() {
        return itemSeq;
    }

    public void setItemSeq(Long itemSeq) {
        this.itemSeq = itemSeq;
    }

    public String getDrugName() {
        return drugName;
    }

    public void setDrugName(String drugName) {
        this.drugName = drugName;
    }

    public String getIngredient() {
        return ingredient;
    }

    public void setIngredient(String ingredient) {
        this.ingredient = ingredient;
    }

    public String getDose() {
        return dose;
    }

    public void setDose(String dose) {
        this.dose = dose;
    }

    public String getIntakeTime() {
        return intakeTime;
    }

    public void setIntakeTime(String intakeTime) {
        this.intakeTime = intakeTime;
    }

    public Integer getIntervalHour() {
        return intervalHour;
    }

    public void setIntervalHour(Integer intervalHour) {
        this.intervalHour = intervalHour;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public Integer getDailyCount() {
        return dailyCount;
    }

    public void setDailyCount(Integer dailyCount) {
        this.dailyCount = dailyCount;
    }

    public String getRecommended() {
        return recommended;
    }

    public void setRecommended(String recommended) {
        this.recommended = recommended;
    }

    public List<MedicationSchedule> getSchedules() {
        return schedules;
    }

    public void setSchedules(List<MedicationSchedule> schedules) {
        this.schedules = schedules;
    }
}
