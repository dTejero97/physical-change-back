package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Instrucciones permanentes de Physical AI de un profesional (se añaden a todos los asistentes y al chat). */
@Entity
@Table(name = "AI_INSTRUCTION")
public class AiInstruction {
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(nullable = false, length = 1000) private String text;
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    protected AiInstruction() {}
    public AiInstruction(Long trainerId) { this.trainerId = trainerId; }

    public Long getTrainerId() { return trainerId; }
    public String getText() { return text; }
    public void setText(String t) { this.text = t; this.updatedAt = LocalDateTime.now(); }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
