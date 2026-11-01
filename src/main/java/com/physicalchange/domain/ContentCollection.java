package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Banco de contenido propio: colección de ideas, ganchos y copys. */
@Entity
@Table(name = "CONTENT_COLLECTION")
public class ContentCollection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 80) private String name;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();

    protected ContentCollection() {}
    public ContentCollection(Long ownerId, String name) { this.ownerId = ownerId; this.name = name; }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public Instant getCreatedAt() { return createdAt; }
}
