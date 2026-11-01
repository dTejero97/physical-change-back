package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Idea para un vídeo o publicación. ownerId null = banco del sistema (solo lectura). */
@Entity
@Table(name = "CONTENT_IDEA")
public class ContentIdea {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID") private Long ownerId;
    @Column(nullable = false, length = 160) private String title;
    @Column(length = 300) private String hook;
    @Column(columnDefinition = "TEXT") private String script;
    @Column(nullable = false, length = 12) private String format;
    @Column(length = 16) private String objective;
    @Column(length = 60) private String theme;
    @Column(length = 330) private String tags;
    @Column(nullable = false, length = 5) private String origin;
    @Column(name = "SOURCE_ID") private Long sourceId;
    @Column(nullable = false) private boolean used;
    @Column(nullable = false) private boolean favorite;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "UPDATED_AT", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long o) { this.ownerId = o; }
    public boolean isBank() { return ownerId == null; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getHook() { return hook; }
    public void setHook(String h) { this.hook = h; }
    public String getScript() { return script; }
    public void setScript(String s) { this.script = s; }
    public String getFormat() { return format; }
    public void setFormat(String f) { this.format = f; }
    public String getObjective() { return objective; }
    public void setObjective(String o) { this.objective = o; }
    public String getTheme() { return theme; }
    public void setTheme(String t) { this.theme = t; }
    public String getTags() { return tags; }
    public void setTags(String t) { this.tags = t; }
    public String getOrigin() { return origin; }
    public void setOrigin(String o) { this.origin = o; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long s) { this.sourceId = s; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean u) { this.used = u; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean f) { this.favorite = f; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
