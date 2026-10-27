package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Servicio que mantiene la plataforma (tabla platform_service; "Entry" para no confundirlo con un @Service). Solo ADMIN. */
@Entity
@Table(name = "PLATFORM_SERVICE")
public class PlatformServiceEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 60) private String name;
    @Column(length = 200) private String purpose;
    @Column(nullable = false, length = 60) private String plan;
    @Column(name = "MONTHLY_COST", nullable = false, precision = 10, scale = 2) private BigDecimal monthlyCost = BigDecimal.ZERO;
    @Column(name = "RENEWAL_DATE") private LocalDate renewalDate;
    @Column(name = "DASHBOARD_URL", length = 300) private String dashboardUrl;
    @Column(name = "FREE_LIMITS", length = 1000) private String freeLimits;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String p) { this.purpose = p; }
    public String getPlan() { return plan; }
    public void setPlan(String p) { this.plan = p; }
    public BigDecimal getMonthlyCost() { return monthlyCost; }
    public void setMonthlyCost(BigDecimal c) { this.monthlyCost = c; }
    public LocalDate getRenewalDate() { return renewalDate; }
    public void setRenewalDate(LocalDate d) { this.renewalDate = d; }
    public String getDashboardUrl() { return dashboardUrl; }
    public void setDashboardUrl(String u) { this.dashboardUrl = u; }
    public String getFreeLimits() { return freeLimits; }
    public void setFreeLimits(String f) { this.freeLimits = f; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
