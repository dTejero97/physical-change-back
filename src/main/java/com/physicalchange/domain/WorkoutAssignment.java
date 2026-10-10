package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "WORKOUT_ASSIGNMENT")
public class WorkoutAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "WORKOUT_ID", nullable = false) private Long workoutId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "SCHEDULED_DATE", nullable = false) private LocalDate scheduledDate;
    @Column(name = "COMPLETED_DATE") private LocalDate completedDate;

    public Long getId() { return id; }
    public Long getWorkoutId() { return workoutId; }
    public void setWorkoutId(Long w) { this.workoutId = w; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public LocalDate getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDate d) { this.scheduledDate = d; }
    public LocalDate getCompletedDate() { return completedDate; }
    public void setCompletedDate(LocalDate d) { this.completedDate = d; }
}
