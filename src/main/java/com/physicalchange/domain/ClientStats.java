package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Estadísticas de actividad del cliente (ADR-012 §5, paso 1). Se recalculan enteras desde el histórico,
 * así que procesar un evento dos veces da el mismo resultado. La fila sirve de cerrojo por cliente.
 */
@Entity
@Table(name = "CLIENT_STATS")
public class ClientStats {
    @Id @Column(name = "CLIENT_ID") private Long clientId;
    @Column(name = "WORKOUTS_COMPLETED", nullable = false) private int workoutsCompleted;
    @Column(name = "ACTIVE_DAYS", nullable = false) private int activeDays;
    @Column(name = "CURRENT_STREAK", nullable = false) private int currentStreak;
    @Column(name = "BEST_STREAK", nullable = false) private int bestStreak;
    @Column(name = "TOTAL_SETS", nullable = false) private int totalSets;
    @Column(name = "TOTAL_REPS", nullable = false) private long totalReps;
    @Column(name = "TOTAL_VOLUME_KG", nullable = false, precision = 14, scale = 2) private BigDecimal totalVolumeKg = BigDecimal.ZERO;
    @Column(name = "LAST_ACTIVITY") private LocalDate lastActivity;
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    protected ClientStats() {}
    public ClientStats(Long clientId) { this.clientId = clientId; }

    public void update(int workoutsCompleted, int activeDays, int currentStreak, int bestStreak, int totalSets,
                       long totalReps, BigDecimal totalVolumeKg, LocalDate lastActivity) {
        this.workoutsCompleted = workoutsCompleted; this.activeDays = activeDays; this.currentStreak = currentStreak;
        this.bestStreak = bestStreak; this.totalSets = totalSets; this.totalReps = totalReps;
        this.totalVolumeKg = totalVolumeKg; this.lastActivity = lastActivity; this.updatedAt = LocalDateTime.now();
    }

    public Long getClientId() { return clientId; }
    public int getWorkoutsCompleted() { return workoutsCompleted; }
    public int getActiveDays() { return activeDays; }
    public int getCurrentStreak() { return currentStreak; }
    public int getBestStreak() { return bestStreak; }
    public int getTotalSets() { return totalSets; }
    public long getTotalReps() { return totalReps; }
    public BigDecimal getTotalVolumeKg() { return totalVolumeKg; }
    public LocalDate getLastActivity() { return lastActivity; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
