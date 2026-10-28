package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.mail.MailService;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Equipo del profesional: invitar colaboradores, darles permisos y asignarles clientes (ADR-011, docs/api-equipo.md).
 * Un miembro de otro profesional responde 404. La baja desactiva al colaborador (cierra sus sesiones, T141)
 * y quita sus asignaciones; no borra nada de lo que creó.
 */
@Service
public class TeamService {
    static final int INVITE_DAYS = 7;

    private final TeamMemberRepository team;
    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final AuthService auth;
    private final AccessService access;
    private final MailService mail;

    public TeamService(TeamMemberRepository t, UserRepository u, UserTokenRepository tk, PasswordEncoder e, JwtService j,
                       AuthService a, AccessService ac, MailService m) {
        team = t; users = u; tokens = tk; enc = e; jwt = j; auth = a; access = ac; mail = m;
    }

    @Transactional(readOnly = true)
    public List<TeamMemberDto> list() {
        return team.findByOwnerIdAndRemovedAtIsNullOrderByInvitedAtDesc(access.current().id()).stream().map(this::toDto).toList();
    }

    @Transactional
    public TeamMemberDto invite(TeamInviteRequest r) {
        Long owner = access.current().id();
        String email = r.email().trim().toLowerCase();
        if (users.findByEmailIgnoreCase(email).isPresent()) throw new ConflictException("Ya existe una cuenta con ese email");
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setFullName(r.fullName().trim());
        u.setRole(Role.COLLABORATOR);
        u.setTrainerId(owner);
        u.setPasswordHash(enc.encode(PasswordResetService.newToken()));   // inservible hasta que acepte
        u.setActive(false);
        u = users.save(u);
        TeamMember m = team.save(new TeamMember(u.getId(), owner));
        sendInvitation(u, owner);
        return toDto(m);
    }

    @Transactional
    public TeamMemberDto resend(Long id) {
        TeamMember m = owned(id);
        if (m.getActivatedAt() != null) throw new ConflictException("El colaborador ya ha aceptado la invitación");
        m.reinvite();
        sendInvitation(users.findById(id).orElseThrow(), m.getOwnerId());
        return toDto(m);
    }

    @Transactional
    public TeamMemberDto permissions(Long id, TeamPermissionsDto r) {
        TeamMember m = owned(id);
        m.setPermissions(r.assignRoutines(), r.manageAppointments(), r.messageClients());
        return toDto(m);
    }

    /** Solo clientes del profesional dueño del equipo (todos los clientes si es ADMIN). */
    @Transactional
    public TeamMemberDto clients(Long id, TeamClientsRequest r) {
        TeamMember m = owned(id);
        Set<Long> ids = new HashSet<>(r.clientIds());
        boolean ownerIsAdmin = users.findById(m.getOwnerId()).map(o -> o.getRole() == Role.ADMIN).orElse(false);
        List<AppUser> found = users.findAllById(ids);
        boolean allMine = found.size() == ids.size() && found.stream().allMatch(c -> c.getRole() == Role.CLIENT
                && (ownerIsAdmin || m.getOwnerId().equals(c.getTrainerId())));
        if (!allMine) throw new ForbiddenException("Solo puedes asignar tus propios clientes");
        m.getClientIds().clear();
        m.getClientIds().addAll(ids);
        return toDto(m);
    }

    @Transactional
    public void remove(Long id) {
        TeamMember m = owned(id);
        m.remove();
        users.findById(id).ifPresent(u -> u.setActive(false));
        tokens.invalidate(id, "TEAM_INVITE");
    }

    /** Para la interfaz del colaborador: de quién es el equipo, qué puede hacer y a quién ve. */
    @Transactional(readOnly = true)
    public TeamMeDto me() {
        TeamMember m = access.collaborator();
        String owner = users.findById(m.getOwnerId()).map(AppUser::getFullName).orElse("-");
        return new TeamMeDto(owner, permissionsOf(m), m.getClientIds().stream().sorted().toList());
    }

    // ---------- Alta pública ----------

    @Transactional(readOnly = true)
    public PublicTeamInvitationDto publicInfo(String token) {
        UserToken t = usable(token);
        AppUser u = users.findById(t.getUserId()).orElseThrow(() -> new NotFoundException("Invitación no disponible"));
        TeamMember m = team.findById(u.getId()).orElseThrow(() -> new NotFoundException("Invitación no disponible"));
        String owner = users.findById(m.getOwnerId()).map(AppUser::getFullName).orElse("-");
        return new PublicTeamInvitationDto(owner, u.getEmail(), u.getFullName());
    }

    @Transactional
    public LoginResponse accept(String token, TeamAcceptRequest r) {
        UserToken t = usable(token);
        AppUser u = users.findById(t.getUserId()).orElseThrow(() -> new NotFoundException("Invitación no disponible"));
        TeamMember m = team.findById(u.getId()).filter(x -> !x.isRemoved())
                .orElseThrow(() -> new NotFoundException("Invitación no disponible"));
        u.setPasswordHash(enc.encode(r.password()));
        u.setActive(true);
        m.activate();
        t.markUsed();
        users.saveAndFlush(u);
        return new LoginResponse(jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()), auth.profile(u));
    }

    // ---------- internos ----------

    private void sendInvitation(AppUser u, Long owner) {
        tokens.invalidate(u.getId(), "TEAM_INVITE");
        String token = PasswordResetService.newToken();
        tokens.save(new UserToken(u.getId(), "TEAM_INVITE", PasswordResetService.sha256(token), LocalDateTime.now().plusDays(INVITE_DAYS)));
        String ownerName = users.findById(owner).map(AppUser::getFullName).orElse("Tu entrenador");
        mail.teamInvitation(u.getEmail(), u.getFullName(), ownerName, token, INVITE_DAYS);
    }

    /** Mismo 404 si no existe, ya se usó o caducó: no da pistas. */
    private UserToken usable(String token) {
        return tokens.findByTokenHash(PasswordResetService.sha256(token))
                .filter(x -> "TEAM_INVITE".equals(x.getPurpose()) && x.usable())
                .orElseThrow(() -> new NotFoundException("Invitación no disponible"));
    }

    /** Miembro del equipo del usuario actual (el ADMIN, de cualquiera); si no, 404. */
    private TeamMember owned(Long id) {
        AuthUser me = access.current();
        return team.findById(id).filter(m -> !m.isRemoved() && (me.role() == Role.ADMIN || m.getOwnerId().equals(me.id())))
                .orElseThrow(() -> new NotFoundException("Colaborador no encontrado"));
    }

    private static TeamPermissionsDto permissionsOf(TeamMember m) {
        return new TeamPermissionsDto(m.isCanAssignRoutines(), m.isCanManageAppointments(), m.isCanMessageClients());
    }

    private TeamMemberDto toDto(TeamMember m) {
        AppUser u = users.findById(m.getUserId()).orElseThrow();
        List<Long> ids = m.getClientIds().stream().sorted().toList();
        int active = (int) users.findAllById(ids).stream().filter(AppUser::isActive).count();
        return new TeamMemberDto(u.getId(), u.getFullName(), u.getEmail(), m.getActivatedAt() == null ? "INVITED" : "ACTIVE",
                m.getInvitedAt(), m.getActivatedAt(), active, ids, permissionsOf(m));
    }
}
