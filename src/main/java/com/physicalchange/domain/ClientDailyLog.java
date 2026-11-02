package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Diario del cliente (T201, D1): sueño, ánimo al levantarse (1-5) y día de descanso. Una fila por cliente y día. */
@Entity
@Table(name = "CLIENT_DAILY_LOG",
       uniqueConstraints = @UniqueConstraint(name = "UQ_CLIENT_DAILY_LOG_DAY", columnNames = {"CLIENT_ID", "LOG_DATE"}))
public class ClientDailyLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "LOG_DATE", nullable = false) private LocalDate logDate;
    @Column(name = "SLEEP_MINUTES") private Integer sleepMinutes;
    private Integer mood;
    @Column(name = "REST_DAY", nullable = false) private boolean restDay;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt;
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public LocalDate getLogDate() { return logDate; }
    public void setLogDate(LocalDate d) { this.logDate = d; }
    public Integer getSleepMinutes() { return sleepMinutes; }
    public void setSleepMinutes(Integer s) { this.sleepMinutes = s; }
    public Integer getMood() { return mood; }
    public void setMood(Integer m) { this.mood = m; }
    public boolean isRestDay() { return restDay; }
    public void setRestDay(boolean r) { this.restDay = r; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime c) { this.createdAt = c; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime u) { this.updatedAt = u; }
}
