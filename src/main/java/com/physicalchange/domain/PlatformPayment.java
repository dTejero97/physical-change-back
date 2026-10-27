package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Pago a un servicio de la plataforma (Render, Vercel, Cloudflare…). Solo ADMIN (ADR-004 §6). */
@Entity
@Table(name = "PLATFORM_PAYMENT")
public class PlatformPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "SERVICE_ID", nullable = false) private Long serviceId;
    @Column(name = "PAYMENT_DATE", nullable = false) private LocalDate paymentDate;
    @Column(nullable = false, length = 120) private String concept;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 8) private String status;
    @Column(name = "FILE_ID") private Long fileId;
    @Column(length = 500) private String notes;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long s) { this.serviceId = s; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate d) { this.paymentDate = d; }
    public String getConcept() { return concept; }
    public void setConcept(String c) { this.concept = c; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long f) { this.fileId = f; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
