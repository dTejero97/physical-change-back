package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Cobro de un cliente al entrenador (cuota, sesión, plan de nutrición...). */
@Entity
@Table(name = "PAYMENT")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 160) private String concept;
    @Column(name = "DUE_DATE", nullable = false) private LocalDate dueDate;
    @Column(name = "PAID_DATE") private LocalDate paidDate;
    @Column(nullable = false, length = 20) private String status = "PENDING";

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getConcept() { return concept; }
    public void setConcept(String c) { this.concept = c; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate d) { this.dueDate = d; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate d) { this.paidDate = d; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
}
