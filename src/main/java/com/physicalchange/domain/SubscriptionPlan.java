package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Plan de suscripción (ADR-004 §3): como Product más la frecuencia (WEEKLY … ANNUAL). */
@Entity
@Table(name = "SUBSCRIPTION_PLAN")
public class SubscriptionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 1000) private String description;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;
    @Column(name = "TAX_RATE", nullable = false) private Short taxRate;
    @Column(nullable = false, length = 12) private String frequency;
    /** Programa de la librería que se asigna solo al empezar la suscripción (V27, T248); null = ninguno. */
    @Column(name = "PROGRAM_ID") private Long programId;
    @Column(nullable = false, length = 10) private String visibility = "HIDDEN";
    @Column(nullable = false, length = 300) private String cover;
    @Column(nullable = false) private boolean archived;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();
    /** Etiquetas que se añaden al cliente al registrar la compra (única automatización, ADR-004 §3). */
    @ElementCollection
    @CollectionTable(name = "PLAN_AUTO_TAG", joinColumns = @JoinColumn(name = "PLAN_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> autoTagIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal p) { this.price = p; }
    public Short getTaxRate() { return taxRate; }
    public void setTaxRate(Short t) { this.taxRate = t; }
    public String getFrequency() { return frequency; }
    public Long getProgramId() { return programId; }
    public void setProgramId(Long p) { this.programId = p; }
    public void setFrequency(String f) { this.frequency = f; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public String getCover() { return cover; }
    public void setCover(String c) { this.cover = c; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean a) { this.archived = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime u) { this.updatedAt = u; }
    public Set<Long> getAutoTagIds() { return autoTagIds; }
}
