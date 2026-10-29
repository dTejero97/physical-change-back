package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "NUTRITION_PLAN")
public class NutritionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "DAILY_KCAL") private Integer dailyKcal;
    @Column(length = 2000) private String notes;
    @Column(name = "TARGET_PROTEIN_G", precision = 6, scale = 1) private BigDecimal targetProteinG;
    @Column(name = "TARGET_CARBS_G", precision = 6, scale = 1) private BigDecimal targetCarbsG;
    @Column(name = "TARGET_FAT_G", precision = 6, scale = 1) private BigDecimal targetFatG;
    /** Sube con cada cambio del profesional; el cliente siempre ve la versión vigente (ADR-012 §2). */
    @Column(nullable = false) private Integer version = 1;
    @Column(name = "UPDATED_AT") private LocalDateTime updatedAt;

    /** Registra un cambio del profesional sobre el plan. */
    public void touch() { this.version = getVersion() + 1; this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public Integer getDailyKcal() { return dailyKcal; }
    public void setDailyKcal(Integer d) { this.dailyKcal = d; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
    public BigDecimal getTargetProteinG() { return targetProteinG; }
    public void setTargetProteinG(BigDecimal p) { this.targetProteinG = p; }
    public BigDecimal getTargetCarbsG() { return targetCarbsG; }
    public void setTargetCarbsG(BigDecimal c) { this.targetCarbsG = c; }
    public BigDecimal getTargetFatG() { return targetFatG; }
    public void setTargetFatG(BigDecimal f) { this.targetFatG = f; }
    public int getVersion() { return version == null ? 1 : version; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
