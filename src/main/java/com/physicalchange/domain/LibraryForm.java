package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Formulario de la Librería. autoSend: se envía automáticamente a los clientes nuevos. */
@Entity
@Table(name = "LIBRARY_FORM")
public class LibraryForm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 2000) private String description;
    @Column(name = "AUTO_SEND", nullable = false) private boolean autoSend;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public boolean isAutoSend() { return autoSend; }
    public void setAutoSend(boolean a) { this.autoSend = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
}
