package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Token de un solo uso enviado por email (T137). Solo se guarda su SHA-256. purpose: RESET_PASSWORD | VERIFY_EMAIL. */
@Entity
@Table(name = "USER_TOKEN")
public class UserToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "USER_ID", nullable = false) private Long userId;
    @Column(nullable = false, length = 20) private String purpose;
    @Column(name = "TOKEN_HASH", nullable = false, length = 64) private String tokenHash;
    @Column(name = "EXPIRES_AT", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "USED_AT") private LocalDateTime usedAt;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    protected UserToken() {}
    public UserToken(Long userId, String purpose, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId; this.purpose = purpose; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }

    public Long getUserId() { return userId; }
    public String getPurpose() { return purpose; }
    public boolean usable() { return usedAt == null && expiresAt.isAfter(LocalDateTime.now()); }
    public void markUsed() { this.usedAt = LocalDateTime.now(); }
}
