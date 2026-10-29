package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Logro conseguido por un cliente: uno por cliente y logro, nunca se repite ni se revierte (ADR-007 §3). */
@Entity
@Table(name = "ACHIEVEMENT_UNLOCK", uniqueConstraints = @UniqueConstraint(name = "UQ_ACHIEVEMENT_UNLOCK", columnNames = {"CLIENT_ID", "ACHIEVEMENT_ID"}))
public class AchievementUnlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "ACHIEVEMENT_ID", nullable = false) private Long achievementId;
    /** Día en que se cumplió la condición según los datos, no el día en que se evaluó. */
    @Column(name = "UNLOCKED_ON", nullable = false) private LocalDate unlockedOn;
    @Column(nullable = false) private Integer points = 0;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    protected AchievementUnlock() {}
    public AchievementUnlock(Long clientId, Long achievementId, LocalDate unlockedOn, int points) {
        this.clientId = clientId; this.achievementId = achievementId; this.unlockedOn = unlockedOn; this.points = points;
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public Long getAchievementId() { return achievementId; }
    public LocalDate getUnlockedOn() { return unlockedOn; }
    public Integer getPoints() { return points; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
