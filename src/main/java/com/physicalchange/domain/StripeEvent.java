package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Evento de webhook ya procesado (ADR-009 §5): Stripe reintenta, así que cada id se aplica una sola vez. */
@Entity
@Table(name = "STRIPE_EVENT")
public class StripeEvent {
    @Id @Column(name = "EVENT_ID", length = 255) private String eventId;
    @Column(nullable = false, length = 80) private String type;
    @Column(name = "RECEIVED_AT", nullable = false) private LocalDateTime receivedAt = LocalDateTime.now();

    protected StripeEvent() {}
    @Column(name = "ACCOUNT_ID", length = 255) private String accountId;
    public StripeEvent(String eventId, String type) { this.eventId = eventId; this.type = type; }
    public StripeEvent(String eventId, String type, String accountId) { this(eventId, type); this.accountId = accountId; }
    public String getAccountId() { return accountId; }

    public String getEventId() { return eventId; }
    public String getType() { return type; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
}
