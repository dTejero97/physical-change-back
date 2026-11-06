package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Vinculación de cuenta Stripe en curso (ADR-018 punto 7, V29): un solo uso y caduca a los 10 min. */
@Entity
@Table(name = "STRIPE_OAUTH_STATE")
public class StripeOauthState {
    @Id @Column(length = 64) private String state;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "ACCOUNT_TYPE", nullable = false, length = 10) private String accountType;
    @Column(name = "STRIPE_ACCOUNT_ID", length = 255) private String stripeAccountId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "EXPIRES_AT", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "USED_AT") private LocalDateTime usedAt;

    protected StripeOauthState() {}
    public StripeOauthState(String state, Long trainerId, String accountType, String stripeAccountId, LocalDateTime expiresAt) {
        this.state = state; this.trainerId = trainerId; this.accountType = accountType; this.stripeAccountId = stripeAccountId; this.expiresAt = expiresAt;
    }

    public String getState() { return state; }
    public Long getTrainerId() { return trainerId; }
    public String getAccountType() { return accountType; }
    public String getStripeAccountId() { return stripeAccountId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime u) { this.usedAt = u; }
}
