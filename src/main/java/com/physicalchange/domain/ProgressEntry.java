package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "PROGRESS_ENTRY")
public class ProgressEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "ENTRY_DATE", nullable = false) private LocalDate entryDate;
    @Column(name = "WEIGHT_KG", precision = 5, scale = 2) private BigDecimal weightKg;
    @Column(name = "BODY_FAT_PCT", precision = 4, scale = 1) private BigDecimal bodyFatPct;
    @Column(length = 500) private String notes;

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate d) { this.entryDate = d; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
    public BigDecimal getBodyFatPct() { return bodyFatPct; }
    public void setBodyFatPct(BigDecimal b) { this.bodyFatPct = b; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
}
