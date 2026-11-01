package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Gancho (HOOK) o copy (COPY). ownerId null = banco del sistema (solo lectura). Los ganchos no usan network, cta ni hashtags. */
@Entity
@Table(name = "CONTENT_SNIPPET")
public class ContentSnippet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID") private Long ownerId;
    @Column(nullable = false, length = 4) private String kind;
    @Column(nullable = false, columnDefinition = "TEXT") private String text;
    @Column(length = 10) private String network;
    @Column(length = 300) private String cta;
    @Column(length = 3100) private String hashtags;
    @Column(length = 60) private String theme;
    @Column(length = 16) private String objective;
    @Column(length = 330) private String tags;
    @Column(nullable = false, length = 5) private String origin;
    @Column(name = "SOURCE_ID") private Long sourceId;
    @Column(nullable = false) private boolean favorite;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "UPDATED_AT", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long o) { this.ownerId = o; }
    public boolean isBank() { return ownerId == null; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getText() { return text; }
    public void setText(String t) { this.text = t; }
    public String getNetwork() { return network; }
    public void setNetwork(String n) { this.network = n; }
    public String getCta() { return cta; }
    public void setCta(String c) { this.cta = c; }
    public String getHashtags() { return hashtags; }
    public void setHashtags(String h) { this.hashtags = h; }
    public String getTheme() { return theme; }
    public void setTheme(String t) { this.theme = t; }
    public String getObjective() { return objective; }
    public void setObjective(String o) { this.objective = o; }
    public String getTags() { return tags; }
    public void setTags(String t) { this.tags = t; }
    public String getOrigin() { return origin; }
    public void setOrigin(String o) { this.origin = o; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long s) { this.sourceId = s; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean f) { this.favorite = f; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
