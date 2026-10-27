package com.physicalchange.domain;

import jakarta.persistence.*;

/** Bono incluido en un producto o en un plan (uno de los dos): sesiones de un tipo de sesión y validez en días (ADR-004 §3). */
@Entity
@Table(name = "BONUS_DEFINITION")
public class BonusDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "PRODUCT_ID") private Long productId;
    @Column(name = "PLAN_ID") private Long planId;
    @Column(name = "SESSION_TYPE_ID", nullable = false) private Long sessionTypeId;
    @Column(nullable = false) private Short sessions;
    @Column(name = "VALID_DAYS", nullable = false) private Short validDays;

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long p) { this.productId = p; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public Long getSessionTypeId() { return sessionTypeId; }
    public void setSessionTypeId(Long s) { this.sessionTypeId = s; }
    public Short getSessions() { return sessions; }
    public void setSessions(Short s) { this.sessions = s; }
    public Short getValidDays() { return validDays; }
    public void setValidDays(Short d) { this.validDays = d; }
}
