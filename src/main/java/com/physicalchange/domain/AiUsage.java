package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Una llamada al proveedor que terminó bien: créditos gastados y neuronas (sin prompts ni respuestas, ADR-003 §10). */
@Entity
@Table(name = "AI_USAGE")
public class AiUsage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 12) private String kind;
    @Column(nullable = false) private Short credits;
    @Column(name = "INPUT_TOKENS", nullable = false) private Integer inputTokens;
    @Column(name = "OUTPUT_TOKENS", nullable = false) private Integer outputTokens;
    @Column(nullable = false) private Integer neurons;
    @Column(nullable = false, length = 80) private String model;
    @Column(name = "REF_TYPE", length = 16) private String refType;
    @Column(name = "REF_ID") private Long refId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public Short getCredits() { return credits; }
    public void setCredits(Short c) { this.credits = c; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer i) { this.inputTokens = i; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer o) { this.outputTokens = o; }
    public Integer getNeurons() { return neurons; }
    public void setNeurons(Integer n) { this.neurons = n; }
    public String getModel() { return model; }
    public void setModel(String m) { this.model = m; }
    public String getRefType() { return refType; }
    public void setRefType(String r) { this.refType = r; }
    public Long getRefId() { return refId; }
    public void setRefId(Long r) { this.refId = r; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
