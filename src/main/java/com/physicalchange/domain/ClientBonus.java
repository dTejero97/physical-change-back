package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Saldo de sesiones de un cliente, concedido al pagar un comprobante (ADR-004 §5). */
@Entity
@Table(name = "CLIENT_BONUS")
public class ClientBonus {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "SESSION_TYPE_ID", nullable = false) private Long sessionTypeId;
    @Column(name = "RECEIPT_ID", nullable = false) private Long receiptId;
    @Column(name = "PRODUCT_NAME", nullable = false, length = 120) private String productName;
    @Column(nullable = false) private Short total;
    @Column(nullable = false) private Short used = 0;
    @Column(name = "EXPIRES_AT", nullable = false) private LocalDate expiresAt;
    @Column(name = "CANCELLED_AT") private LocalDateTime cancelledAt;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public Long getSessionTypeId() { return sessionTypeId; }
    public void setSessionTypeId(Long s) { this.sessionTypeId = s; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long r) { this.receiptId = r; }
    public String getProductName() { return productName; }
    public void setProductName(String n) { this.productName = n; }
    public Short getTotal() { return total; }
    public void setTotal(Short t) { this.total = t; }
    public Short getUsed() { return used; }
    public void setUsed(Short u) { this.used = u; }
    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate e) { this.expiresAt = e; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime c) { this.cancelledAt = c; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
