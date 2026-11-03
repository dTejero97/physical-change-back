package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Una red destino de una publicación: PENDING, PUBLISHING, PUBLISHED, MANUAL_DUE, FAILED o CANCELLED. */
@Entity
@Table(name = "CONTENT_POST_TARGET")
public class ContentPostTarget {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "POST_ID", nullable = false) private ContentPost post;
    @Column(nullable = false, length = 10) private String network;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "CLAIMED_AT") private Instant claimedAt;
    @Column(nullable = false) private short attempts;
    @Column(name = "EXTERNAL_ID", length = 200) private String externalId;
    @Column(name = "EXTERNAL_URL", length = 500) private String externalUrl;
    @Column(name = "PUBLISHED_AT") private Instant publishedAt;
    @Column(length = 500) private String error;

    protected ContentPostTarget() {}
    ContentPostTarget(ContentPost post, String network, String status) { this.post = post; this.network = network; this.status = status; }

    public Long getId() { return id; }
    public ContentPost getPost() { return post; }
    public String getNetwork() { return network; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Instant getClaimedAt() { return claimedAt; }
    public int getAttempts() { return attempts; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String e) { this.externalId = e; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String e) { this.externalUrl = e; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant p) { this.publishedAt = p; }
    public String getError() { return error; }
    public void setError(String e) { this.error = e; }
    /** Vuelve a PENDING sin enlaces ni errores (reprogramar o restaurar). */
    public void reset() { status = "PENDING"; claimedAt = null; error = null; externalId = null; externalUrl = null; publishedAt = null; }
}
