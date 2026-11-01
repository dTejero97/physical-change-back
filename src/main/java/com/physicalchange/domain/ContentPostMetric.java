package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Totales acumulados de una publicación en una red a una fecha (ADR-014 §7). null = sin datos, nunca 0. */
@Entity
@Table(name = "CONTENT_POST_METRIC")
public class ContentPostMetric {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "POST_ID", nullable = false) private Long postId;
    @Column(nullable = false, length = 10) private String network;
    @Column(name = "MEASURED_ON", nullable = false) private LocalDate measuredOn;
    private Long views;
    private Long reach;
    private Long likes;
    private Long comments;
    private Long shares;
    private Long saves;
    @Column(nullable = false, length = 6) private String source;
    @Column(name = "CREATED_BY") private Long createdBy;
    @Column(name = "CREATED_AT", nullable = false) private Instant createdAt = Instant.now();

    protected ContentPostMetric() {}
    public ContentPostMetric(Long postId, String network, LocalDate measuredOn, String source, Long createdBy) {
        this.postId = postId; this.network = network; this.measuredOn = measuredOn; this.source = source; this.createdBy = createdBy;
    }

    public void setValues(Long views, Long reach, Long likes, Long comments, Long shares, Long saves) {
        this.views = views; this.reach = reach; this.likes = likes; this.comments = comments; this.shares = shares; this.saves = saves;
    }

    public Long getId() { return id; }
    public Long getPostId() { return postId; }
    public String getNetwork() { return network; }
    public LocalDate getMeasuredOn() { return measuredOn; }
    public Long getViews() { return views; }
    public Long getReach() { return reach; }
    public Long getLikes() { return likes; }
    public Long getComments() { return comments; }
    public Long getShares() { return shares; }
    public Long getSaves() { return saves; }
    public String getSource() { return source; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
