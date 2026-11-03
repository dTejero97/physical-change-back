package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Cuenta de una red social vinculada por un profesional (ADR-014 §5). Solo se guardan CONNECTED, DISABLED, EXPIRED y ERROR;
 * NOT_CONFIGURED y DISCONNECTED se calculan. Los tokens van cifrados (TokenCipher) y nunca salen en DTOs ni logs.
 */
@Entity
@Table(name = "SOCIAL_ACCOUNT")
public class SocialAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 10) private String network;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "EXTERNAL_ID", length = 100) private String externalId;
    @Column(name = "EXTERNAL_USERNAME", length = 120) private String externalUsername;
    @Column(name = "ACCESS_TOKEN_ENC", nullable = false, columnDefinition = "TEXT") private String accessTokenEnc;
    @Column(name = "REFRESH_TOKEN_ENC", columnDefinition = "TEXT") private String refreshTokenEnc;
    @Column(name = "TOKEN_EXPIRES_AT") private Instant tokenExpiresAt;
    @Column(length = 500) private String scopes;
    @Column(name = "CONNECTED_AT", nullable = false) private Instant connectedAt = Instant.now();
    @Column(name = "CONNECTED_BY") private Long connectedBy;
    @Column(name = "LAST_SYNC_AT") private Instant lastSyncAt;
    @Column(name = "SYNC_STARTED_AT") private Instant syncStartedAt;
    @Column(name = "LAST_ERROR", length = 500) private String lastError;
    @Column(name = "ERROR_COUNT", nullable = false) private short errorCount;
    @Column(name = "UPDATED_AT", nullable = false) private Instant updatedAt = Instant.now();

    protected SocialAccount() {}
    public SocialAccount(Long ownerId, String network, Long connectedBy) {
        this.ownerId = ownerId; this.network = network; this.connectedBy = connectedBy; this.status = "CONNECTED";
    }

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getNetwork() { return network; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String e) { this.externalId = e; }
    public String getExternalUsername() { return externalUsername; }
    public void setExternalUsername(String e) { this.externalUsername = e; }
    public String getAccessTokenEnc() { return accessTokenEnc; }
    public void setAccessTokenEnc(String a) { this.accessTokenEnc = a; }
    public String getRefreshTokenEnc() { return refreshTokenEnc; }
    public void setRefreshTokenEnc(String r) { this.refreshTokenEnc = r; }
    public Instant getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(Instant t) { this.tokenExpiresAt = t; }
    public String getScopes() { return scopes; }
    public void setScopes(String s) { this.scopes = s; }
    public Instant getConnectedAt() { return connectedAt; }
    public Long getConnectedBy() { return connectedBy; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant l) { this.lastSyncAt = l; }
    public Instant getSyncStartedAt() { return syncStartedAt; }
    public void setSyncStartedAt(Instant s) { this.syncStartedAt = s; }
    public String getLastError() { return lastError; }
    public void setLastError(String l) { this.lastError = l; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int c) { this.errorCount = (short) c; }
    public Instant getUpdatedAt() { return updatedAt; }
}
