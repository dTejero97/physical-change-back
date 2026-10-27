package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Cupón de descuento (ADR-004 §3). El uso se descuenta con CouponRepository.consume (UPDATE condicional). */
@Entity
@Table(name = "COUPON")
public class Coupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 30) private String code;
    @Column(length = 300) private String description;
    @Column(nullable = false, length = 8) private String type;
    @Column(name = "DISCOUNT_VALUE", nullable = false, precision = 10, scale = 2) private BigDecimal value;
    @Column(name = "APPLIES_TO", nullable = false, length = 8) private String appliesTo;
    @Column(name = "MAX_USES") private Integer maxUses;
    @Column(nullable = false) private Integer uses = 0;
    @Column(name = "EXPIRES_AT") private LocalDate expiresAt;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getCode() { return code; }
    public void setCode(String c) { this.code = c; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getType() { return type; }
    public void setType(String t) { this.type = t; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }
    public String getAppliesTo() { return appliesTo; }
    public void setAppliesTo(String a) { this.appliesTo = a; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer m) { this.maxUses = m; }
    public Integer getUses() { return uses; }
    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate e) { this.expiresAt = e; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
