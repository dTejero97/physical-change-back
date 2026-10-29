package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Aviso en la app para un usuario (ADR-012 §4). refType/refId apuntan a lo que lo originó. */
@Entity
@Table(name = "NOTIFICATION")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "USER_ID", nullable = false) private Long userId;
    @Column(nullable = false, length = 30) private String type;
    @Column(nullable = false, length = 120) private String title;
    @Column(length = 500) private String body;
    @Column(name = "REF_TYPE", length = 30) private String refType;
    @Column(name = "REF_ID") private Long refId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "READ_AT") private LocalDateTime readAt;

    protected Notification() {}
    public Notification(Long userId, String type, String title, String body, String refType, Long refId) {
        this.userId = userId; this.type = type; this.title = title; this.body = body; this.refType = refType; this.refId = refId;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getRefType() { return refType; }
    public Long getRefId() { return refId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void markRead() { if (readAt == null) readAt = LocalDateTime.now(); }
}
