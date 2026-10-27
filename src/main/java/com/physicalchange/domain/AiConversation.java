package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Conversación del panel de Physical AI. Se borra a los 30 días (RGPD, ADR-003 §10). */
@Entity
@Table(name = "AI_CONVERSATION")
public class AiConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String title;
    @Column(length = 60) private String context;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContext() { return context; }
    public void setContext(String c) { this.context = c; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
}
