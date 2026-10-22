package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Etiqueta de clientes de un entrenador. automations: JSON con la configuración (TagAutomations). */
@Entity
@Table(name = "CLIENT_TAG")
public class ClientTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 60) private String name;
    @Column(nullable = false, length = 4000) private String automations = "{}";
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getAutomations() { return automations; }
    public void setAutomations(String a) { this.automations = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
