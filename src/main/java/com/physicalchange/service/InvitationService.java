package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Invitaciones de clientes. Por email: un solo uso. Enlace genérico (sin email): reutilizable hasta caducar.
 * Las de email se envían por correo al crearlas y al reenviarlas (T137); el enlace <front>/invitacion/<token> se puede compartir igualmente.
 */
@Service
public class InvitationService {
    static final int VALID_DAYS = 14;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientInvitationRepository invitations;
    private final ClientTagRepository tags;
    private final UserRepository users;
    private final ClientProfileService profiles;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final AuthService auth;
    private final AccessService access;
    private final SubscriptionPlanRepository plans;
    private final SubscriptionService subscriptions;
    private final com.physicalchange.mail.MailService mail;

    public InvitationService(ClientInvitationRepository i, ClientTagRepository t, UserRepository u, ClientProfileService p,
                             PasswordEncoder e, JwtService j, AuthService a, AccessService ac, SubscriptionPlanRepository pl,
                             SubscriptionService s, com.physicalchange.mail.MailService m) {
        invitations = i; tags = t; users = u; profiles = p; enc = e; jwt = j; auth = a; access = ac;
        plans = pl; subscriptions = s; mail = m;
    }

    @Transactional(readOnly = true)
    public List<InvitationDto> list() {
        AuthUser me = access.current();
        List<ClientInvitation> list = me.role() == Role.ADMIN
                ? invitations.findByEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc()
                : invitations.findByTrainerIdAndEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc(me.id());
        return list.stream().map(InvitationService::toDto).toList();
    }

    @Transactional
    public InvitationDto create(InvitationRequest r) {
        AuthUser me = access.current();
        String email = r.email().trim().toLowerCase();
        if (users.findByEmailIgnoreCase(email).isPresent()) throw new ConflictException("Ya existe un usuario con ese email");
        if (invitations.existsByTrainerIdAndEmailIgnoreCaseAndAcceptedAtIsNull(me.id(), email))
            throw new ConflictException("Ya hay una invitación pendiente para ese email");
        ClientInvitation i = base(me.id(), r.tagId(), r.planId());
        i.setEmail(email);
        i.setName(r.name().trim());
        i.setPlanName(r.planName() == null || r.planName().isBlank() ? null : r.planName().trim());
        ClientInvitation saved = invitations.save(i);
        sendMail(saved);
        return toDto(saved);
    }

    /** Enlace genérico reutilizable ("Generar enlace de invitación"). */
    @Transactional
    public InvitationDto createLink(InvitationLinkRequest r) {
        AuthUser me = access.current();
        return toDto(invitations.save(base(me.id(), r == null ? null : r.tagId(), r == null ? null : r.planId())));
    }

    /** Renueva fecha y caducidad y vuelve a enviar el correo. */
    @Transactional
    public InvitationDto resend(Long id) {
        ClientInvitation i = owned(id);
        if (i.getAcceptedAt() != null) throw new ConflictException("La invitación ya fue aceptada");
        LocalDateTime now = LocalDateTime.now();
        i.setInvitedAt(now);
        i.setExpiresAt(now.plusDays(VALID_DAYS));
        if (i.getEmail() != null) sendMail(i);
        return toDto(i);
    }

    private void sendMail(ClientInvitation i) {
        String trainer = users.findById(i.getTrainerId()).map(AppUser::getFullName).orElse("Tu entrenador");
        mail.invitation(i.getEmail(), i.getName(), trainer, i.getToken(), VALID_DAYS);
    }

    @Transactional
    public void delete(Long id) { invitations.delete(owned(id)); }

    // ---------- público (sin sesión) ----------

    @Transactional(readOnly = true)
    public PublicInvitationDto publicInfo(String token) {
        ClientInvitation i = usable(token);
        String trainer = users.findById(i.getTrainerId()).map(AppUser::getFullName).orElse("Tu entrenador");
        return new PublicInvitationDto(trainer, i.getEmail(), i.getName(), i.getExpiresAt().isBefore(LocalDateTime.now()));
    }

    /** Crea el cliente, le asigna entrenador, ficha y etiqueta, y devuelve su sesión. */
    @Transactional
    public LoginResponse accept(String token, AcceptInvitationRequest r) {
        ClientInvitation i = usable(token);
        if (i.getExpiresAt().isBefore(LocalDateTime.now())) throw new GoneException("La invitación ha caducado. Pide una nueva a tu entrenador");
        String email = i.getEmail() != null ? i.getEmail() : r.email() == null ? "" : r.email().trim().toLowerCase();
        if (email.isBlank()) throw new BadRequestException("email: es obligatorio");
        if (users.findByEmailIgnoreCase(email).isPresent()) throw new ConflictException("Ya existe una cuenta con ese email");

        AppUser u = new AppUser();
        u.setEmail(email);
        u.setFullName(r.fullName().trim());
        u.setPasswordHash(enc.encode(r.password()));
        u.setRole(Role.CLIENT);
        u.setTrainerId(i.getTrainerId());
        u = users.save(u);

        profiles.onClientCreated(u.getId(), i.getTrainerId(), i.getTagId() == null ? List.of() : List.of(i.getTagId()), i.getPlanName());
        if (i.getPlanId() != null) subscriptions.startFromInvitation(i.getTrainerId(), u.getId(), i.getPlanId());
        if (i.getEmail() != null) { i.setAcceptedAt(LocalDateTime.now()); i.setClientId(u.getId()); }
        return new LoginResponse(jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())), auth.profile(u));
    }

    // ---------- internos ----------

    private ClientInvitation base(Long trainerId, Long tagId, Long planId) {
        if (planId != null && plans.findById(planId).filter(p -> p.getTrainerId().equals(trainerId) && !p.isArchived()).isEmpty())
            throw new BadRequestException("El plan de pago no existe");
        if (tagId != null) {
            ClientTag t = tags.findById(tagId).orElseThrow(() -> new BadRequestException("La etiqueta no existe"));
            if (!Objects.equals(t.getTrainerId(), trainerId)) throw new BadRequestException("La etiqueta no es tuya");
        }
        LocalDateTime now = LocalDateTime.now();
        ClientInvitation i = new ClientInvitation();
        i.setTrainerId(trainerId);
        i.setTagId(tagId);
        i.setPlanId(planId);
        i.setToken(newToken());
        i.setInvitedAt(now);
        i.setExpiresAt(now.plusDays(VALID_DAYS));
        return i;
    }

    /** Existe y no se ha usado (las de email son de un solo uso). Mismo 404 en ambos casos para no dar pistas. */
    private ClientInvitation usable(String token) {
        return invitations.findByToken(token)
                .filter(i -> i.getAcceptedAt() == null)
                .orElseThrow(() -> new NotFoundException("Invitación no encontrada o ya utilizada"));
    }

    private ClientInvitation owned(Long id) {
        ClientInvitation i = invitations.findById(id).orElseThrow(() -> new NotFoundException("Invitación no encontrada"));
        access.assertOwnerOrAdmin(i.getTrainerId());
        return i;
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static InvitationDto toDto(ClientInvitation i) {
        return new InvitationDto(i.getId(), i.getEmail(), i.getName(), i.getTagId(), i.getPlanName(), i.getToken(),
                i.getInvitedAt(), i.getExpiresAt(), i.getPlanId());
    }
}
