package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Plan asignado a un cliente. Cada periodo genera un comprobante PENDING en la puesta al día (ADR-004 §4). */
@Entity
@Table(name = "CLIENT_SUBSCRIPTION")
public class ClientSubscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "PLAN_ID", nullable = false) private Long planId;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;
    @Column(name = "COUPON_ID") private Long couponId;
    @Column(name = "START_DATE", nullable = false) private LocalDate startDate;
    @Column(name = "NEXT_PERIOD_START", nullable = false) private LocalDate nextPeriodStart;
    @Column(nullable = false, length = 10) private String status = "ACTIVE";
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "CANCELLED_AT") private LocalDateTime cancelledAt;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal p) { this.price = p; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long c) { this.couponId = c; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate d) { this.startDate = d; }
    public LocalDate getNextPeriodStart() { return nextPeriodStart; }
    public void setNextPeriodStart(LocalDate d) { this.nextPeriodStart = d; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime c) { this.cancelledAt = c; }
}
