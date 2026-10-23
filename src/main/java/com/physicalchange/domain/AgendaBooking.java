package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Reserva de un cliente en una sesión. status: BOOKED | WAITLIST | CANCELLED | ATTENDED | NO_SHOW. */
@Entity
@Table(name = "AGENDA_BOOKING")
public class AgendaBooking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "EVENT_ID", nullable = false) private Long eventId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long e) { this.eventId = e; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; this.updatedAt = LocalDateTime.now(); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
