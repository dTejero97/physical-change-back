package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Historial de una solicitud de cambio: CREATED, ACCEPTED o REJECTED, con autor y nota. */
@Entity
@Table(name = "CHANGE_REQUEST_EVENT")
public class ChangeRequestEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "REQUEST_ID", nullable = false) private Long requestId;
    @Column(name = "OCCURRED_AT", nullable = false) private LocalDateTime at = LocalDateTime.now();
    @Column(name = "ACTOR_ID", nullable = false) private Long actorId;
    @Column(nullable = false, length = 10) private String action;
    @Column(length = 500) private String note;

    protected ChangeRequestEvent() {}
    public ChangeRequestEvent(Long requestId, Long actorId, String action, String note) {
        this.requestId = requestId; this.actorId = actorId; this.action = action; this.note = note;
    }

    public Long getId() { return id; }
    public Long getRequestId() { return requestId; }
    public LocalDateTime getAt() { return at; }
    public Long getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getNote() { return note; }
}
