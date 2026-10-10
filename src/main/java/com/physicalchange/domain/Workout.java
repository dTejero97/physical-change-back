package com.physicalchange.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "WORKOUT")
public class Workout {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 2000) private String notes;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
}
