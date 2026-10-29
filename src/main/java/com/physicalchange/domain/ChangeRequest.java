package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Solicitud de cambio del cliente sobre su plan (ADR-012 §1). Crearla nunca modifica el plan:
 * solo la aceptación explícita del profesional responsable lo hace. PENDING → ACCEPTED | REJECTED (finales).
 */
@Entity
@Table(name = "CHANGE_REQUEST", uniqueConstraints = @UniqueConstraint(name = "UQ_CHANGE_REQUEST_PENDING", columnNames = {"CLIENT_ID", "PENDING_KEY"}))
public class ChangeRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 10) private String kind;
    @Column(nullable = false, length = 20) private String action;
    @Column(nullable = false, length = 10) private String status = "PENDING";
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "PROFESSIONAL_ID", nullable = false) private Long professionalId;

    @Column(name = "ASSIGNMENT_ID") private Long assignmentId;
    @Column(name = "WORKOUT_ID") private Long workoutId;
    @Column(name = "WORKOUT_ITEM_ID") private Long workoutItemId;
    @Column(name = "TARGET_POSITION") private Integer targetPosition;
    @Column(name = "TARGET_EXERCISE_ID") private Long targetExerciseId;
    @Column(name = "EXERCISE_ID") private Long exerciseId;
    private Integer sets;
    private Integer reps;
    @Column(name = "WEIGHT_KG", precision = 6, scale = 2) private BigDecimal weightKg;
    @Column(name = "REST_SECONDS") private Integer restSeconds;

    @Column(name = "PLAN_ID") private Long planId;
    @Column(name = "PLAN_VERSION") private Integer planVersion;
    @Column(name = "MEAL_ID") private Long mealId;
    @Column(name = "FOOD_ID") private Long foodId;
    @Column(precision = 7, scale = 1) private BigDecimal grams;
    private Integer kcal;
    @Column(name = "PROTEIN_G", precision = 6, scale = 1) private BigDecimal proteinG;
    @Column(name = "CARBS_G", precision = 6, scale = 1) private BigDecimal carbsG;
    @Column(name = "FAT_G", precision = 6, scale = 1) private BigDecimal fatG;

    @Column(length = 500) private String reason;
    @Column(name = "DECISION_NOTE", length = 500) private String decisionNote;
    @Column(name = "PENDING_KEY", length = 80) private String pendingKey;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "DECIDED_AT") private LocalDateTime decidedAt;
    @Column(name = "DECIDED_BY") private Long decidedBy;
    @Column(name = "APPLIED_WORKOUT_ID") private Long appliedWorkoutId;
    @Column(name = "COPIED_WORKOUT", nullable = false) private boolean copiedWorkout;

    public boolean isPending() { return "PENDING".equals(status); }

    /** Cierra la solicitud: estado final, quién y cuándo; libera la clave de duplicados. */
    public void decide(String newStatus, Long by, String note) {
        this.status = newStatus; this.decidedBy = by; this.decidedAt = LocalDateTime.now();
        this.decisionNote = note; this.pendingKey = null;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getAction() { return action; }
    public void setAction(String a) { this.action = a; }
    public String getStatus() { return status; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getProfessionalId() { return professionalId; }
    public void setProfessionalId(Long p) { this.professionalId = p; }
    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long a) { this.assignmentId = a; }
    public Long getWorkoutId() { return workoutId; }
    public void setWorkoutId(Long w) { this.workoutId = w; }
    public Long getWorkoutItemId() { return workoutItemId; }
    public void setWorkoutItemId(Long w) { this.workoutItemId = w; }
    public Integer getTargetPosition() { return targetPosition; }
    public void setTargetPosition(Integer p) { this.targetPosition = p; }
    public Long getTargetExerciseId() { return targetExerciseId; }
    public void setTargetExerciseId(Long e) { this.targetExerciseId = e; }
    public Long getExerciseId() { return exerciseId; }
    public void setExerciseId(Long e) { this.exerciseId = e; }
    public Integer getSets() { return sets; }
    public void setSets(Integer s) { this.sets = s; }
    public Integer getReps() { return reps; }
    public void setReps(Integer r) { this.reps = r; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
    public Integer getRestSeconds() { return restSeconds; }
    public void setRestSeconds(Integer r) { this.restSeconds = r; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer v) { this.planVersion = v; }
    public Long getMealId() { return mealId; }
    public void setMealId(Long m) { this.mealId = m; }
    public Long getFoodId() { return foodId; }
    public void setFoodId(Long f) { this.foodId = f; }
    public BigDecimal getGrams() { return grams; }
    public void setGrams(BigDecimal g) { this.grams = g; }
    public Integer getKcal() { return kcal; }
    public void setKcal(Integer k) { this.kcal = k; }
    public BigDecimal getProteinG() { return proteinG; }
    public void setProteinG(BigDecimal p) { this.proteinG = p; }
    public BigDecimal getCarbsG() { return carbsG; }
    public void setCarbsG(BigDecimal c) { this.carbsG = c; }
    public BigDecimal getFatG() { return fatG; }
    public void setFatG(BigDecimal f) { this.fatG = f; }
    public String getReason() { return reason; }
    public void setReason(String r) { this.reason = r; }
    public String getDecisionNote() { return decisionNote; }
    public String getPendingKey() { return pendingKey; }
    public void setPendingKey(String k) { this.pendingKey = k; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public Long getDecidedBy() { return decidedBy; }
    public Long getAppliedWorkoutId() { return appliedWorkoutId; }
    public void setAppliedWorkoutId(Long w) { this.appliedWorkoutId = w; }
    public boolean isCopiedWorkout() { return copiedWorkout; }
    public void setCopiedWorkout(boolean c) { this.copiedWorkout = c; }
}
