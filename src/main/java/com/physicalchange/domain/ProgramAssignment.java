package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Asignación de un programa de la librería a un cliente (V27, docs/api-entrenamiento-automatico.md). AUTO = al empezar
 * una suscripción cuyo plan tiene programa (una sola por suscripción); MANUAL = la hace un profesional.
 */
@Entity
@Table(name = "PROGRAM_ASSIGNMENT")
public class ProgramAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "PROGRAM_ID") private Long programId;
    @Column(name = "PROGRAM_NAME", nullable = false, length = 120) private String programName;
    @Column(name = "START_DATE", nullable = false) private LocalDate startDate;
    @Column(name = "END_DATE", nullable = false) private LocalDate endDate;
    @Column(name = "SUBSCRIPTION_ID", unique = true) private Long subscriptionId;
    @Column(nullable = false, length = 10) private String origin;
    @Column(name = "CREATED_BY") private Long createdBy;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getProgramId() { return programId; }
    public void setProgramId(Long p) { this.programId = p; }
    public String getProgramName() { return programName; }
    public void setProgramName(String n) { this.programName = n; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate d) { this.startDate = d; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate d) { this.endDate = d; }
    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long s) { this.subscriptionId = s; }
    public String getOrigin() { return origin; }
    public void setOrigin(String o) { this.origin = o; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long u) { this.createdBy = u; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
