package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Colaborador del equipo de un profesional (ADR-011): permisos opcionales y clientes asignados. */
@Entity
@Table(name = "TEAM_MEMBER")
public class TeamMember {
    @Id @Column(name = "USER_ID") private Long userId;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(name = "INVITED_AT", nullable = false) private LocalDateTime invitedAt = LocalDateTime.now();
    @Column(name = "ACTIVATED_AT") private LocalDateTime activatedAt;
    @Column(name = "REMOVED_AT") private LocalDateTime removedAt;
    @Column(name = "CAN_ASSIGN_ROUTINES", nullable = false) private boolean canAssignRoutines;
    @Column(name = "CAN_MANAGE_APPOINTMENTS", nullable = false) private boolean canManageAppointments;
    @Column(name = "CAN_MESSAGE_CLIENTS", nullable = false) private boolean canMessageClients;

    @ElementCollection
    @CollectionTable(name = "COLLABORATOR_CLIENT", joinColumns = @JoinColumn(name = "COLLABORATOR_ID"))
    @Column(name = "CLIENT_ID")
    private Set<Long> clientIds = new HashSet<>();

    protected TeamMember() {}
    public TeamMember(Long userId, Long ownerId) { this.userId = userId; this.ownerId = ownerId; }

    public Long getUserId() { return userId; }
    public Long getOwnerId() { return ownerId; }
    public LocalDateTime getInvitedAt() { return invitedAt; }
    public void reinvite() { this.invitedAt = LocalDateTime.now(); }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void activate() { this.activatedAt = LocalDateTime.now(); }
    public boolean isRemoved() { return removedAt != null; }
    public void remove() { this.removedAt = LocalDateTime.now(); this.clientIds.clear(); }
    public boolean isCanAssignRoutines() { return canAssignRoutines; }
    public boolean isCanManageAppointments() { return canManageAppointments; }
    public boolean isCanMessageClients() { return canMessageClients; }
    public void setPermissions(boolean assign, boolean appointments, boolean messages) {
        this.canAssignRoutines = assign; this.canManageAppointments = appointments; this.canMessageClients = messages;
    }
    public Set<Long> getClientIds() { return clientIds; }
}
