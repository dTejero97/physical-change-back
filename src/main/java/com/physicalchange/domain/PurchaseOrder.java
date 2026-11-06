package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Un intento de pago en Stripe Checkout (ADR-009, V20). RECEIPT = pagar un comprobante pendiente; PLAN = contratar un plan
 * con renovación automática. El importe lo fija el servidor al crearlo; solo el webhook firmado lo pasa a PAID.
 */
@Entity
@Table(name = "PURCHASE_ORDER")
public class PurchaseOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 8) private String kind;
    @Column(name = "RECEIPT_ID") private Long receiptId;
    @Column(name = "PLAN_ID") private Long planId;
    @Column(name = "SUBSCRIPTION_ID") private Long subscriptionId;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 10) private String status = "PENDING";
    @Column(name = "STRIPE_SESSION_ID", unique = true, length = 255) private String stripeSessionId;
    @Column(name = "STRIPE_PAYMENT_INTENT", length = 255) private String stripePaymentIntent;
    @Column(name = "STRIPE_ACCOUNT_ID", length = 255) private String stripeAccountId;
    @Column(name = "PLATFORM_FEE_AMOUNT", nullable = false, precision = 10, scale = 2) private BigDecimal platformFeeAmount = BigDecimal.ZERO;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "EXPIRES_AT", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "PAID_AT") private LocalDateTime paidAt;

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long r) { this.receiptId = r; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long s) { this.subscriptionId = s; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getStripeSessionId() { return stripeSessionId; }
    public void setStripeSessionId(String s) { this.stripeSessionId = s; }
    public String getStripePaymentIntent() { return stripePaymentIntent; }
    public void setStripePaymentIntent(String p) { this.stripePaymentIntent = p; }
    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String s) { this.stripeAccountId = s; }
    public BigDecimal getPlatformFeeAmount() { return platformFeeAmount; }
    public void setPlatformFeeAmount(BigDecimal p) { this.platformFeeAmount = p; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime e) { this.expiresAt = e; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime p) { this.paidAt = p; }
}
