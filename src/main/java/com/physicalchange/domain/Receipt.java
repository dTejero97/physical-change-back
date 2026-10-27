package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Comprobante de pago (ADR-004 §1). Inmutable: tras crearlo solo cambian status, paidDate, method (al pagar) y los datos de anulación.
 * Las líneas van en ReceiptLine (receiptId).
 */
@Entity
@Table(name = "RECEIPT")
public class Receipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "ISSUE_DATE", nullable = false) private LocalDate issueDate;
    @Column(name = "DUE_DATE", nullable = false) private LocalDate dueDate;
    @Column(length = 500) private String notes;
    @Column(nullable = false, length = 10) private String method;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "PAID_DATE") private LocalDate paidDate;
    @Column(nullable = false, length = 12) private String origin;
    @Column(name = "SUBSCRIPTION_ID") private Long subscriptionId;
    @Column(name = "PERIOD_START") private LocalDate periodStart;
    @Column(name = "PERIOD_END") private LocalDate periodEnd;
    @Column(name = "COUPON_ID") private Long couponId;
    @Column(name = "DISCOUNT_NAME", length = 60) private String discountName;
    @Column(name = "DISCOUNT_PCT", precision = 5, scale = 2) private BigDecimal discountPct;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal subtotal;
    @Column(name = "DISCOUNT_AMOUNT", nullable = false, precision = 12, scale = 2) private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal total;
    @Column(name = "VOID_REASON", length = 200) private String voidReason;
    @Column(name = "VOIDED_AT") private LocalDateTime voidedAt;
    @Column(name = "WAS_PAID", nullable = false) private boolean wasPaid;
    @Column(name = "LEGACY_PAYMENT_ID") private Long legacyPaymentId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate d) { this.issueDate = d; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate d) { this.dueDate = d; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate d) { this.paidDate = d; }
    public String getOrigin() { return origin; }
    public void setOrigin(String o) { this.origin = o; }
    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long s) { this.subscriptionId = s; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate d) { this.periodStart = d; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate d) { this.periodEnd = d; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long c) { this.couponId = c; }
    public String getDiscountName() { return discountName; }
    public void setDiscountName(String n) { this.discountName = n; }
    public BigDecimal getDiscountPct() { return discountPct; }
    public void setDiscountPct(BigDecimal p) { this.discountPct = p; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal s) { this.subtotal = s; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal a) { this.discountAmount = a; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal t) { this.total = t; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String r) { this.voidReason = r; }
    public LocalDateTime getVoidedAt() { return voidedAt; }
    public void setVoidedAt(LocalDateTime v) { this.voidedAt = v; }
    public boolean isWasPaid() { return wasPaid; }
    public void setWasPaid(boolean w) { this.wasPaid = w; }
    public Long getLegacyPaymentId() { return legacyPaymentId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
