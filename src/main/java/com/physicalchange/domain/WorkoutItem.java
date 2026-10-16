package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Ejercicio dentro de una rutina, con series, repeticiones, peso y descanso planificados. */
@Entity
@Table(name = "WORKOUT_ITEM")
public class WorkoutItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "WORKOUT_ID", nullable = false) private Long workoutId;
    @Column(name = "EXERCISE_ID", nullable = false) private Long exerciseId;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false) private Integer sets;
    @Column(nullable = false) private Integer reps;
    @Column(name = "WEIGHT_KG", nullable = false, precision = 6, scale = 2) private BigDecimal weightKg = BigDecimal.ZERO;
    @Column(name = "REST_SECONDS") private Integer restSeconds;
    @Column(length = 300) private String notes;

    public Long getId() { return id; }
    public Long getWorkoutId() { return workoutId; }
    public void setWorkoutId(Long w) { this.workoutId = w; }
    public Long getExerciseId() { return exerciseId; }
    public void setExerciseId(Long e) { this.exerciseId = e; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
    public Integer getSets() { return sets; }
    public void setSets(Integer s) { this.sets = s; }
    public Integer getReps() { return reps; }
    public void setReps(Integer r) { this.reps = r; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
    public Integer getRestSeconds() { return restSeconds; }
    public void setRestSeconds(Integer r) { this.restSeconds = r; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
}
