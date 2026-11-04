package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.ClientOnboardingRepository;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.*;
import com.physicalchange.service.audit.AuditAction;
import com.physicalchange.service.audit.AuditService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users; private final PasswordEncoder enc; private final JwtService jwt; private final AccessService access;
    private final LoginAttempts attempts; private final ClientOnboardingRepository onboardings; private final FileService files;
    private final AuditService audit;
    /** Hash de relleno: si el email no existe se compara igual, para que el tiempo de respuesta no revele qué cuentas existen. */
    private final String dummyHash;
    /** Tamaño máximo del logo del profesional (T183). */
    static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;
    public AuthService(UserRepository u, PasswordEncoder e, JwtService j, AccessService a, LoginAttempts la, ClientOnboardingRepository o,
                       FileService f, AuditService au) {
        users = u; enc = e; jwt = j; access = a; attempts = la; onboardings = o; files = f; audit = au; dummyHash = e.encode("relleno-para-tiempo-constante");
    }

    /** DTO simple (flag propio del usuario). */
    public static UserDto toDto(AppUser u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), u.isNutritionEnabled(),
                u.isTrainingEnabled(), u.getNutritionistId(), null, null, null, null);
    }

    /**
     * DTO de perfil: para un CLIENT, los servicios indican si tiene responsable de nutrición y de entrenamiento (ADR-013)
     * onboardingCompleted si ya terminó el asistente de bienvenida y el nombre de sus responsables vigentes (T185);
     * todo ello null en el resto de roles.
     */
    @Transactional(readOnly = true)
    public UserDto profile(AppUser u) {
        boolean n = u.isNutritionEnabled(), t = u.isTrainingEnabled();
        Boolean onboarding = null;
        String trainingName = null, nutritionName = null;
        // Logo (T183): el propio para el profesional; para el cliente, el de su profesional (entrenamiento o, si no, nutrición).
        Long logoOwner = u.getRole() == Role.TRAINER || u.getRole() == Role.ADMIN ? u.getId() : null;
        if (u.getRole() == Role.CLIENT) {
            Long nutrition = access.nutritionResponsibleOf(u), training = access.trainingResponsibleOf(u);
            n = nutrition != null;
            t = training != null;
            onboarding = onboardings.isCompleted(u.getId());
            trainingName = nameOf(training);
            nutritionName = nameOf(nutrition);
            logoOwner = training != null ? training : nutrition;
        }
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), n, t, u.getNutritionistId(), onboarding,
                trainingName, nutritionName, logoOf(logoOwner));
    }

    /** URL firmada (1 h) del logo de ese profesional, o null. */
    private String logoOf(Long userId) {
        Long file = userId == null ? null : users.findById(userId).map(AppUser::getLogoFileId).orElse(null);
        return file == null ? null : files.url(file);
    }

    /** Logo del profesional (T183, docs/api-logo.md): un archivo IMAGE suyo de hasta 2 MB. */
    @Transactional
    public UserDto setLogo(LogoRequest r) {
        AppUser u = professional();
        StoredFile f = files.owned(r.fileId(), "IMAGE");
        if (f.getSizeBytes() > LOGO_MAX_BYTES) throw new PayloadTooLargeException("El logo no puede pasar de 2 MB");
        u.setLogoFileId(f.getId());
        audit.record(AuditAction.SETTINGS_CHANGED, "USER", u.getId(), null, "Logo cambiado");
        return profile(u);
    }

    /** Quita el logo; el archivo se queda en su Librería. */
    @Transactional
    public UserDto removeLogo() {
        AppUser u = professional();
        u.setLogoFileId(null);
        audit.record(AuditAction.SETTINGS_CHANGED, "USER", u.getId(), null, "Logo quitado");
        return profile(u);
    }

    private AppUser professional() {
        AppUser u = currentUser();
        if (u.getRole() != Role.TRAINER && u.getRole() != Role.ADMIN) throw new ForbiddenException("Solo un profesional tiene logo");
        return u;
    }

    private String nameOf(Long userId) {
        return userId == null ? null : users.findById(userId).map(AppUser::getFullName).orElse(null);
    }

    /** Servicios del profesional (ADR-013 §1): entrenamiento, nutrición o ambos. */
    @Transactional
    public UserDto updateServices(ServicesRequest r) {
        AppUser u = currentUser();
        if (u.getRole() != Role.TRAINER && u.getRole() != Role.ADMIN) throw new ForbiddenException("Solo un profesional ofrece servicios");
        if (!r.training() && !r.nutrition()) throw new BadRequestException("Ofrece al menos entrenamiento o nutrición");
        u.setTrainingEnabled(r.training());
        u.setNutritionEnabled(r.nutrition());
        audit.record(AuditAction.SETTINGS_CHANGED, "USER", u.getId(), null,
                "Servicios: entrenamiento " + (r.training() ? "sí" : "no") + ", nutrición " + (r.nutrition() ? "sí" : "no"));
        return profile(u);
    }

    /** Acepta email o el usuario "admin". */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest r) {
        String key = r.username().trim().toLowerCase();
        try { attempts.check(key); }
        catch (TooManyRequestsException e) { audit.recordNow(null, null, AuditAction.LOGIN_LOCKED, "USER", null, null, "Usuario: " + AuditService.maskLogin(key)); throw e; }
        AppUser u = users.findByEmailIgnoreCase(key).filter(AppUser::isActive).orElse(null);
        boolean ok = enc.matches(r.password(), u == null ? dummyHash : u.getPasswordHash());
        if (u == null || !ok) {
            attempts.failed(key);
            // Auditoría (ADR-016): el usuario tecleado enmascarado, nunca la contraseña.
            audit.recordNow(u == null ? null : u.getId(), u == null ? null : u.getRole().name(), AuditAction.LOGIN_FAILED, "USER",
                    u == null ? null : u.getId(), null, "Usuario: " + AuditService.maskLogin(key));
            throw new BadCredentialsException("x");
        }
        attempts.succeeded(key);
        audit.recordAs(u.getId(), u.getRole().name(), AuditAction.LOGIN_OK, "USER", u.getId(), null, null);
        return session(u);
    }

    @Transactional
    public UserDto updateProfile(UpdateProfileRequest r) {
        AppUser u = currentUser();
        u.setFullName(r.fullName());
        return profile(u);
    }

    @Transactional
    /** Cierra todas las sesiones (también las de otros dispositivos) y devuelve una nueva para quien la ha cambiado. */
    public LoginResponse changePassword(ChangePasswordRequest r) {
        AppUser u = currentUser();
        if (!enc.matches(r.currentPassword(), u.getPasswordHash())) throw new ForbiddenException("La contraseña actual no es correcta");
        u.setPasswordHash(enc.encode(r.newPassword()));
        users.saveAndFlush(u);
        audit.record(AuditAction.PASSWORD_CHANGED, "USER", u.getId(), null, null);
        return session(u);
    }

    /** Cerrar sesión en el servidor: invalida este token y los de cualquier otro dispositivo. */
    @Transactional
    public void logout() {
        AppUser u = currentUser();
        u.revokeSessions();
        audit.record(AuditAction.LOGOUT, "USER", u.getId(), null, null);
    }

    private LoginResponse session(AppUser u) {
        return new LoginResponse(jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()), profile(u));
    }

    private AppUser currentUser() {
        return users.findById(access.current().id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }
}
