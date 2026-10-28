package com.physicalchange.security;

import com.physicalchange.domain.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.TeamMemberRepository;
import com.physicalchange.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Control de propiedad de recursos: la regla real de "quién ve qué". */
@Service
public class AccessService {
    private final UserRepository users;
    private final TeamMemberRepository team;
    public AccessService(UserRepository users, TeamMemberRepository team) { this.users = users; this.team = team; }

    public AuthUser current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthUser u)) throw new ForbiddenException("No autenticado");
        return u;
    }

    /** Dueño de los datos con los que se trabaja: el profesional para un colaborador (ADR-011), el propio usuario en el resto. */
    public Long workspaceId() {
        AuthUser me = current();
        return me.role() == Role.COLLABORATOR ? member(me.id()).getOwnerId() : me.id();
    }

    /** Ficha del colaborador actual (activo y no dado de baja). */
    public TeamMember collaborator() {
        AuthUser me = current();
        if (me.role() != Role.COLLABORATOR) throw new ForbiddenException("Solo para colaboradores");
        return member(me.id());
    }

    /** Clientes asignados al colaborador actual. */
    public Set<Long> assignedClients() { return collaborator().getClientIds(); }

    /** Un colaborador necesita el permiso indicado; el resto de roles pasa. */
    public void assertPermission(boolean granted, String what) {
        if (current().role() == Role.COLLABORATOR && !granted) throw new ForbiddenException("No tienes permiso para " + what);
    }

    /** ADMIN: siempre. CLIENT: solo él mismo. TRAINER: solo sus clientes. COLLABORATOR: solo los asignados. */
    public void assertCanAccessClient(Long clientId) {
        AuthUser me = current();
        if (me.role() == Role.ADMIN) return;
        if (me.role() == Role.CLIENT) {
            if (!me.id().equals(clientId)) throw new ForbiddenException("Recurso de otro cliente");
            return;
        }
        if (me.role() == Role.COLLABORATOR) {
            if (!member(me.id()).getClientIds().contains(clientId)) throw new ForbiddenException("No es un cliente asignado");
            return;
        }
        AppUser c = users.findById(clientId).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (c.getRole() != Role.CLIENT || !me.id().equals(c.getTrainerId())) throw new ForbiddenException("No es tu cliente");
    }

    /** Propietario, ADMIN, o colaborador del espacio dueño del recurso. */
    public void assertOwnerOrAdmin(Long ownerId) {
        AuthUser me = current();
        if (me.role() == Role.ADMIN || me.id().equals(ownerId)) return;
        if (me.role() == Role.COLLABORATOR && member(me.id()).getOwnerId().equals(ownerId)) return;
        throw new ForbiddenException("No eres el propietario");
    }

    private TeamMember member(Long userId) {
        return team.findById(userId).filter(m -> !m.isRemoved())
                .orElseThrow(() -> new ForbiddenException("No formas parte de ningún equipo"));
    }
}
