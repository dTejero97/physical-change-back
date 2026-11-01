package com.physicalchange.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Publicación del calendario (ADR-014 §6): contenido, formato, medio, hora programada y estado global.
 * Cada red destino es un ContentPostTarget con su propio estado. version: bloqueo optimista (el job también la sube al tomarla).
 */
@Entity
@Table(name = "CONTENT_POST")
public class ContentPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(name = "CREATED_BY") private Long createdBy;
    @Column(name = "UPDATED_BY") private Long updatedBy;
    @Column(nullable = false, length = 160) private String title;
    @Column(columnDefinition = "TEXT") private String body;
    @Column(length = 3100) private String hashtags;
    @Column(nullable = false, length = 12) private String format;
    @Column(name = "MEDIA_FILE_ID") private Long mediaFileId;
    @Column(name = "MEDIA_URL", length = 500) private String mediaUrl;
    @Column(name = "IDEA_ID") private Long ideaId;
    @Column(nullable = false, length = 10) private String status = "DRAFT";
    @Column(name = "SCHEDULED_AT") private Instant scheduledAt;
    @Column(name = "TIME_ZONE", nullable = false, length = 40) private String timeZone = "Europe/Madrid";
    @Column(name = "DUE_AT") private Instant dueAt;
    @Version @Column(nullable = false) private int version;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "UPDATED_AT", nullable = false) private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id") @BatchSize(size = 100)
    private List<ContentPostTarget> targets = new ArrayList<>();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    /** Marca la publicación como cambiada por un usuario: siempre queda sucia, así @Version sube aunque solo cambien sus destinos. */
    public void changedBy(Long userId) { this.updatedBy = userId; this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long o) { this.ownerId = o; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long c) { this.createdBy = c; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long u) { this.updatedBy = u; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b; }
    public String getHashtags() { return hashtags; }
    public void setHashtags(String h) { this.hashtags = h; }
    public String getFormat() { return format; }
    public void setFormat(String f) { this.format = f; }
    public Long getMediaFileId() { return mediaFileId; }
    public void setMediaFileId(Long m) { this.mediaFileId = m; }
    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String m) { this.mediaUrl = m; }
    public Long getIdeaId() { return ideaId; }
    public void setIdeaId(Long i) { this.ideaId = i; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant s) { this.scheduledAt = s; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String t) { this.timeZone = t; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant d) { this.dueAt = d; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ContentPostTarget> getTargets() { return targets; }

    public Optional<ContentPostTarget> target(String network) {
        return targets.stream().filter(t -> t.getNetwork().equals(network)).findFirst();
    }
    public ContentPostTarget addTarget(String network, String status) {
        ContentPostTarget t = new ContentPostTarget(this, network, status);
        targets.add(t);
        return t;
    }
}
