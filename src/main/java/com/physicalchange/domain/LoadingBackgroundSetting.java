package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Fila única (id = 1): fondo activo de todo el sitio y quién/cuándo lo cambió (L-03). changedBy null = valor inicial. */
@Entity
@Table(name = "LOADING_BACKGROUND_SETTING")
public class LoadingBackgroundSetting {
    public static final short SINGLETON = 1;

    @Id private Short id = SINGLETON;
    @Column(name = "ACTIVE_BACKGROUND_ID", nullable = false) private Long activeBackgroundId;
    @Column(name = "CHANGED_BY") private Long changedBy;
    @Column(name = "CHANGED_AT") private LocalDateTime changedAt;

    protected LoadingBackgroundSetting() {}
    public LoadingBackgroundSetting(Long activeBackgroundId) { this.activeBackgroundId = activeBackgroundId; }

    public Short getId() { return id; }
    public Long getActiveBackgroundId() { return activeBackgroundId; }
    public Long getChangedBy() { return changedBy; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void change(Long backgroundId, Long by, LocalDateTime at) { activeBackgroundId = backgroundId; changedBy = by; changedAt = at; }
}
