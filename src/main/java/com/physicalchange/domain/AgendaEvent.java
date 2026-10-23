package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Evento de la agenda (una fila por ocurrencia). kind: SESSION | MEETING | REMINDER | PRIVATE.
 * seriesKey une las ocurrencias de una serie recurrente (ADR-002). cover: clave predefinida o "file:<id>".
 */
@Entity
@Table(name = "AGENDA_EVENT")
public class AgendaEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 10) private String kind;
    @Column(nullable = false, length = 120) private String title;
    @Column(length = 16) private String emoji;
    @Column(length = 2000) private String description;
    @Column(length = 10) private String mode;
    @Column(length = 200) private String location;
    @Column(name = "MEETING_URL", length = 500) private String meetingUrl;
    @Column(name = "SHOW_AS", length = 5) private String showAs;
    @Column(name = "SESSION_TYPE_ID") private Long sessionTypeId;
    @Column(length = 300) private String cover;
    @Column(name = "START_AT", nullable = false) private LocalDateTime startAt;
    @Column(name = "END_AT", nullable = false) private LocalDateTime endAt;
    @Column(name = "SERIES_KEY", length = 36) private String seriesKey;
    private Integer capacity;
    @Column(nullable = false, length = 4) private String bookable = "NO";
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    /** Etiquetas de clientes que pueden reservar cuando bookable = TAGS. */
    @ElementCollection
    @CollectionTable(name = "AGENDA_EVENT_TAG", joinColumns = @JoinColumn(name = "EVENT_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> tagIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String e) { this.emoji = e; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getMode() { return mode; }
    public void setMode(String m) { this.mode = m; }
    public String getLocation() { return location; }
    public void setLocation(String l) { this.location = l; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String u) { this.meetingUrl = u; }
    public String getShowAs() { return showAs; }
    public void setShowAs(String s) { this.showAs = s; }
    public Long getSessionTypeId() { return sessionTypeId; }
    public void setSessionTypeId(Long s) { this.sessionTypeId = s; }
    public String getCover() { return cover; }
    public void setCover(String c) { this.cover = c; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime s) { this.startAt = s; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime e) { this.endAt = e; }
    public String getSeriesKey() { return seriesKey; }
    public void setSeriesKey(String s) { this.seriesKey = s; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer c) { this.capacity = c; }
    public String getBookable() { return bookable; }
    public void setBookable(String b) { this.bookable = b; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Set<Long> getTagIds() { return tagIds; }
}
