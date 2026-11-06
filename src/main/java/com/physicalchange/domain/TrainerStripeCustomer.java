package com.physicalchange.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Cliente de Stripe de un cliente en la cuenta de un profesional (ADR-018 punto 5, V29). Un cus_… existe en una sola
 * cuenta: si el profesional vincula otra, el cliente se crea de nuevo en la nueva.
 */
@Entity
@Table(name = "TRAINER_STRIPE_CUSTOMER")
@IdClass(TrainerStripeCustomer.Key.class)
public class TrainerStripeCustomer {
    @Id @Column(name = "CLIENT_ID") private Long clientId;
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(name = "STRIPE_CUSTOMER_ID", nullable = false, unique = true, length = 255) private String stripeCustomerId;
    @Column(name = "STRIPE_ACCOUNT_ID", nullable = false, length = 255) private String stripeAccountId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    protected TrainerStripeCustomer() {}
    public TrainerStripeCustomer(Long clientId, Long trainerId, String stripeAccountId, String stripeCustomerId) {
        this.clientId = clientId; this.trainerId = trainerId; this.stripeAccountId = stripeAccountId; this.stripeCustomerId = stripeCustomerId;
    }

    public Long getClientId() { return clientId; }
    public Long getTrainerId() { return trainerId; }
    public String getStripeCustomerId() { return stripeCustomerId; }
    public String getStripeAccountId() { return stripeAccountId; }

    public void moveTo(String stripeAccountId, String stripeCustomerId) {
        this.stripeAccountId = stripeAccountId;
        this.stripeCustomerId = stripeCustomerId;
    }

    public static class Key implements Serializable {
        public Long clientId;
        public Long trainerId;
        public Key() {}
        public Key(Long clientId, Long trainerId) { this.clientId = clientId; this.trainerId = trainerId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key i && Objects.equals(clientId, i.clientId) && Objects.equals(trainerId, i.trainerId);
        }
        @Override public int hashCode() { return Objects.hash(clientId, trainerId); }
    }
}
