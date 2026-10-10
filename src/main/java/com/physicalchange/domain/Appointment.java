package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "APPOINTMENT")
public class Appointment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(name = "START_AT", nullable = false) private LocalDateTime startAt;
    @Column(name = "END_AT", nullable = false) private LocalDateTime endAt;
    @Column(nullable = false, length = 20) private String status = "BOOKED";
    @Column(length = 200) private String title;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime s) { this.startAt = s; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime e) { this.endAt = e; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
}
