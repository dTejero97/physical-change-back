package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Logro. trainerId nulo = del sistema (conditionType SYSTEM). Los propios definen una condición
 * (WEIGHT_EXERCISE, TOTAL_REPS, STREAK, BODY_WEIGHT, TIME_WITH_YOU) y una duración.
 */
@Entity
@Table(name = "ACHIEVEMENT")
public class Achievement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CATEGORY_ID", nullable = false) private Long categoryId;
    @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(nullable = false, length = 80) private String name;
    @Column(length = 500) private String description;
    @Column(name = "GROUP_LABEL", length = 80) private String groupLabel;
    @Column(nullable = false, length = 30) private String badge = "custom";
    @Column(name = "BADGE_FILE_ID") private Long badgeFileId;
    @Column(nullable = false, length = 6) private String tier = "TEAL";
    @Column(name = "CONDITION_TYPE", nullable = false, length = 16) private String conditionType;
    @Column(name = "EXERCISE_ID") private Long exerciseId;
    @Column(name = "WEIGHT_KG", precision = 6, scale = 2) private BigDecimal weightKg;
    private Integer reps;
    private Integer days;
    @Column(name = "TARGET_KG", precision = 5, scale = 1) private BigDecimal targetKg;
    private Integer months;
    @Column(name = "DURATION_TYPE", nullable = false, length = 16) private String durationType = "UNLIMITED";
    @Column(name = "START_DATE") private LocalDate startDate;
    @Column(name = "END_DATE") private LocalDate endDate;
    @Column(name = "DAYS_AFTER") private Integer daysAfter;
    @Column(nullable = false) private Integer position = 0;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    /** Solo en los del sistema: qué se mide y cuánto hace falta (ADR-007 §6, ADR-012 §5). */
    @Column(length = 20) private String metric;
    @Column(precision = 12, scale = 2) private BigDecimal threshold;
    @Column(nullable = false) private Integer points = 10;

    public Long getId() { return id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long c) { this.categoryId = c; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public boolean isSystem() { return trainerId == null; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getGroupLabel() { return groupLabel; }
    public void setGroupLabel(String g) { this.groupLabel = g; }
    public String getBadge() { return badge; }
    public void setBadge(String b) { this.badge = b; }
    public Long getBadgeFileId() { return badgeFileId; }
    public void setBadgeFileId(Long f) { this.badgeFileId = f; }
    public String getTier() { return tier; }
    public void setTier(String t) { this.tier = t; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String c) { this.conditionType = c; }
    public Long getExerciseId() { return exerciseId; }
    public void setExerciseId(Long e) { this.exerciseId = e; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
    public Integer getReps() { return reps; }
    public void setReps(Integer r) { this.reps = r; }
    public Integer getDays() { return days; }
    public void setDays(Integer d) { this.days = d; }
    public BigDecimal getTargetKg() { return targetKg; }
    public void setTargetKg(BigDecimal t) { this.targetKg = t; }
    public Integer getMonths() { return months; }
    public void setMonths(Integer m) { this.months = m; }
    public String getDurationType() { return durationType; }
    public void setDurationType(String d) { this.durationType = d; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate d) { this.startDate = d; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate d) { this.endDate = d; }
    public Integer getDaysAfter() { return daysAfter; }
    public void setDaysAfter(Integer d) { this.daysAfter = d; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getMetric() { return metric; }
    public void setMetric(String m) { this.metric = m; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal t) { this.threshold = t; }
    public int getPoints() { return points == null ? 0 : points; }
    public void setPoints(Integer p) { this.points = p; }
}
