package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Tipo de sesión de un profesional (Nutrición, Yoga...). color: uno de la paleta del contrato (#RRGGBB). */
@Entity
@Table(name = "SESSION_TYPE")
public class SessionType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 60) private String name;
    @Column(nullable = false, length = 7) private String color;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getColor() { return color; }
    public void setColor(String c) { this.color = c; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
