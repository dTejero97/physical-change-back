package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** state de OAuth (ADR-014 §3): solo su SHA-256, un uso, caduca a los 10 minutos, atado al usuario y a la red. */
@Entity
@Table(name = "SOCIAL_OAUTH_STATE")
public class SocialOauthState {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "STATE_HASH", nullable = false, unique = true, length = 64) private String stateHash;
    @Column(name = "USER_ID", nullable = false) private Long userId;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 10) private String network;
    @Column(name = "CODE_VERIFIER_ENC", columnDefinition = "TEXT") private String codeVerifierEnc;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "EXPIRES_AT", nullable = false) private Instant expiresAt;
    @Column(name = "USED_AT") private Instant usedAt;

    protected SocialOauthState() {}
    public SocialOauthState(String stateHash, Long userId, Long ownerId, String network, String codeVerifierEnc, Instant expiresAt) {
        this.stateHash = stateHash; this.userId = userId; this.ownerId = ownerId; this.network = network;
        this.codeVerifierEnc = codeVerifierEnc; this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getStateHash() { return stateHash; }
    public Long getUserId() { return userId; }
    public Long getOwnerId() { return ownerId; }
    public String getNetwork() { return network; }
    public String getCodeVerifierEnc() { return codeVerifierEnc; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}
