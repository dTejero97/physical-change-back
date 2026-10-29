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
    /** Rutina original si esta es la copia de una sola sesión (cambio aceptado, ADR-012 §2); null en la librería. */
    @Column(name = "SESSION_COPY_OF") private Long sessionCopyOf;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
    public Long getSessionCopyOf() { return sessionCopyOf; }
    public void setSessionCopyOf(Long id) { this.sessionCopyOf = id; }
}
