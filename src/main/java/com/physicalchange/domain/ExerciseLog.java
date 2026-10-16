package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Una serie realmente levantada por el cliente: peso x repeticiones. */
@Entity
@Table(name = "EXERCISE_LOG")
public class ExerciseLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "ASSIGNMENT_ID") private Long assignmentId;
    @Column(name = "WORKOUT_ITEM_ID") private Long workoutItemId;
    @Column(name = "EXERCISE_ID", nullable = false) private Long exerciseId;
    @Column(name = "LOG_DATE", nullable = false) private LocalDate logDate;
    @Column(name = "SET_NUMBER", nullable = false) private Integer setNumber;
    @Column(nullable = false) private Integer reps;
    @Column(name = "WEIGHT_KG", nullable = false, precision = 6, scale = 2) private BigDecimal weightKg;

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long a) { this.assignmentId = a; }
    public Long getWorkoutItemId() { return workoutItemId; }
    public void setWorkoutItemId(Long w) { this.workoutItemId = w; }
    public Long getExerciseId() { return exerciseId; }
    public void setExerciseId(Long e) { this.exerciseId = e; }
    public LocalDate getLogDate() { return logDate; }
    public void setLogDate(LocalDate d) { this.logDate = d; }
    public Integer getSetNumber() { return setNumber; }
    public void setSetNumber(Integer s) { this.setNumber = s; }
    public Integer getReps() { return reps; }
    public void setReps(Integer r) { this.reps = r; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
}
