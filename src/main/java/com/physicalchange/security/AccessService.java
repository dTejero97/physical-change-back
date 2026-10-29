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

    // ---------- Responsables de entrenamiento y de nutrición (ADR-013) ----------

    /** Responsable de nutrición vigente: su nutricionista (si sigue ofreciendo nutrición) o, si no tiene, su entrenador si la ofrece. */
    public Long nutritionResponsibleOf(AppUser client) {
        Long id = client.getNutritionistId() != null ? client.getNutritionistId() : client.getTrainerId();
        if (id == null) return null;
        return users.findById(id).filter(p -> p.getRole() == Role.TRAINER && p.isActive() && p.isNutritionEnabled()).map(AppUser::getId).orElse(null);
    }

    /** Responsable de entrenamiento: su entrenador, si ofrece entrenamiento. */
    public Long trainingResponsibleOf(AppUser client) {
        if (client.getTrainerId() == null) return null;
        return users.findById(client.getTrainerId()).filter(AppUser::isTrainingEnabled).map(AppUser::getId).orElse(null);
    }

    /** Cambiar la nutrición de un cliente: ADMIN o su responsable de nutrición vigente. Nadie más, tampoco su entrenador si no lo es. */
    public void assertNutritionResponsible(Long clientId) {
        AuthUser me = current();
        if (me.role() == Role.ADMIN) return;
        if (me.role() != Role.TRAINER || !me.id().equals(nutritionResponsibleOf(client(clientId))))
            throw new ForbiddenException("Solo el responsable de nutrición del cliente puede hacerlo");
    }

    /** Ver la nutrición de un cliente: su responsable de nutrición o quien ya tiene acceso al cliente. */
    public void assertCanReadNutrition(Long clientId) {
        AuthUser me = current();
        if (me.role() == Role.TRAINER && me.id().equals(nutritionResponsibleOf(client(clientId)))) return;
        assertCanAccessClient(clientId);
    }

    /** Crear o cambiar rutinas: el espacio de trabajo tiene que ofrecer entrenamiento. ADMIN siempre. */
    public void assertOffersTraining() {
        if (current().role() == Role.ADMIN) return;
        AppUser pro = users.findById(workspaceId()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        if (!pro.isTrainingEnabled()) throw new ForbiddenException("Tu cuenta no ofrece entrenamiento");
    }

    private AppUser client(Long clientId) {
        return users.findById(clientId).filter(u -> u.getRole() == Role.CLIENT).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
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
