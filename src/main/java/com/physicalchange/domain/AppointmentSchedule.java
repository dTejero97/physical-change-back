package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Horario de citas: franjas semanales en las que los clientes reservan citas individuales. slots: JSON. */
@Entity
@Table(name = "APPOINTMENT_SCHEDULE")
public class AppointmentSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String title;
    @Column(length = 16) private String emoji;
    @Column(nullable = false, length = 10) private String mode;
    @Column(length = 5) private String source;
    @Column(name = "MEETING_URL", length = 500) private String meetingUrl;
    @Column(length = 200) private String location;
    @Column(name = "SESSION_TYPE_ID", nullable = false) private Long sessionTypeId;
    @Column(length = 2000) private String description;
    @Column(length = 300) private String cover;
    @Column(name = "START_DATE", nullable = false) private LocalDate startDate;
    @Column(name = "END_DATE", nullable = false) private LocalDate endDate;
    @Column(name = "DURATION_MIN", nullable = false) private Integer durationMin;
    @Column(nullable = false, length = 4000) private String slots = "[]";
    @Column(nullable = false, length = 4) private String visibility = "ALL";
    @Column(nullable = false) private boolean active = true;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection
    @CollectionTable(name = "APPOINTMENT_SCHEDULE_TAG", joinColumns = @JoinColumn(name = "SCHEDULE_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> tagIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String e) { this.emoji = e; }
    public String getMode() { return mode; }
    public void setMode(String m) { this.mode = m; }
    public String getSource() { return source; }
    public void setSource(String s) { this.source = s; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String u) { this.meetingUrl = u; }
    public String getLocation() { return location; }
    public void setLocation(String l) { this.location = l; }
    public Long getSessionTypeId() { return sessionTypeId; }
    public void setSessionTypeId(Long s) { this.sessionTypeId = s; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getCover() { return cover; }
    public void setCover(String c) { this.cover = c; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate d) { this.startDate = d; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate d) { this.endDate = d; }
    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer d) { this.durationMin = d; }
    public String getSlots() { return slots; }
    public void setSlots(String s) { this.slots = s; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Set<Long> getTagIds() { return tagIds; }
}
