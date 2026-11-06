package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Cuenta Stripe Connect del profesional (ADR-018, V29). Solo guarda el id de cuenta: nunca claves. */
@Entity
@Table(name = "TRAINER_STRIPE_ACCOUNT")
public class TrainerStripeAccount {
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(name = "STRIPE_ACCOUNT_ID", nullable = false, unique = true, length = 255) private String stripeAccountId;
    @Column(name = "ACCOUNT_TYPE", nullable = false, length = 10) private String accountType = "STANDARD";
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "CHARGES_ENABLED", nullable = false) private boolean chargesEnabled;
    @Column(name = "PAYOUTS_ENABLED", nullable = false) private boolean payoutsEnabled;
    @Column(name = "DETAILS_SUBMITTED", nullable = false) private boolean detailsSubmitted;
    @Column(name = "REQUIREMENTS_DUE", nullable = false, length = 2000) private String requirementsDue = "[]";
    @Column(name = "LINKED_AT", nullable = false) private LocalDateTime linkedAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();
    @Column(name = "UNLINKED_AT") private LocalDateTime unlinkedAt;

    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String s) { this.stripeAccountId = s; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String t) { this.accountType = t; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public boolean isChargesEnabled() { return chargesEnabled; }
    public void setChargesEnabled(boolean b) { this.chargesEnabled = b; }
    public boolean isPayoutsEnabled() { return payoutsEnabled; }
    public void setPayoutsEnabled(boolean b) { this.payoutsEnabled = b; }
    public boolean isDetailsSubmitted() { return detailsSubmitted; }
    public void setDetailsSubmitted(boolean b) { this.detailsSubmitted = b; }
    public String getRequirementsDue() { return requirementsDue; }
    public void setRequirementsDue(String r) { this.requirementsDue = r; }
    public LocalDateTime getLinkedAt() { return linkedAt; }
    public void setLinkedAt(LocalDateTime l) { this.linkedAt = l; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime u) { this.updatedAt = u; }
    public LocalDateTime getUnlinkedAt() { return unlinkedAt; }
    public void setUnlinkedAt(LocalDateTime u) { this.unlinkedAt = u; }
}
