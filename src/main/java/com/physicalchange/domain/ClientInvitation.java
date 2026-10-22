package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Invitación a darse de alta como cliente. email NULL = enlace genérico reutilizable. */
@Entity
@Table(name = "CLIENT_INVITATION")
public class ClientInvitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(length = 160) private String email;
    @Column(length = 120) private String name;
    @Column(name = "TAG_ID") private Long tagId;
    @Column(name = "PLAN_NAME", length = 120) private String planName;
    @Column(nullable = false, unique = true, length = 64) private String token;
    @Column(name = "INVITED_AT", nullable = false) private LocalDateTime invitedAt;
    @Column(name = "EXPIRES_AT", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "ACCEPTED_AT") private LocalDateTime acceptedAt;
    @Column(name = "CLIENT_ID") private Long clientId;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getEmail() { return email; }
    public void setEmail(String e) { this.email = e; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long t) { this.tagId = t; }
    public String getPlanName() { return planName; }
    public void setPlanName(String p) { this.planName = p; }
    public String getToken() { return token; }
    public void setToken(String t) { this.token = t; }
    public LocalDateTime getInvitedAt() { return invitedAt; }
    public void setInvitedAt(LocalDateTime i) { this.invitedAt = i; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime e) { this.expiresAt = e; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime a) { this.acceptedAt = a; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
}
