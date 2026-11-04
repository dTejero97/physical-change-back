package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Evento del registro de auditoría (ADR-016, V26). Solo se inserta; la purga borra lo que tiene más de 12 meses. */
@Entity
@Table(name = "AUDIT_EVENT")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OCCURRED_AT", nullable = false) private Instant occurredAt = Instant.now();
    @Column(name = "ACTOR_ID") private Long actorId;
    @Column(name = "ACTOR_ROLE", length = 20) private String actorRole;
    @Column(nullable = false, length = 40) private String action;
    @Column(name = "ENTITY_TYPE", length = 40) private String entityType;
    @Column(name = "ENTITY_ID") private Long entityId;
    @Column(name = "CLIENT_ID") private Long clientId;
    @Column(length = 300) private String summary;
    @Column(length = 45) private String ip;
    @Column(name = "REQUEST_ID", length = 36) private String requestId;

    protected AuditEvent() {}

    public AuditEvent(Long actorId, String actorRole, String action, String entityType, Long entityId, Long clientId, String summary,
                      String ip, String requestId) {
        this.actorId = actorId; this.actorRole = actorRole; this.action = action; this.entityType = entityType; this.entityId = entityId;
        this.clientId = clientId; this.summary = summary; this.ip = ip; this.requestId = requestId;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getActorId() { return actorId; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public Long getClientId() { return clientId; }
    public String getSummary() { return summary; }
    public String getIp() { return ip; }
    public String getRequestId() { return requestId; }
}
