package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.ClientOnboardingRepository;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users; private final PasswordEncoder enc; private final JwtService jwt; private final AccessService access;
    private final LoginAttempts attempts; private final ClientOnboardingRepository onboardings;
    /** Hash de relleno: si el email no existe se compara igual, para que el tiempo de respuesta no revele qué cuentas existen. */
    private final String dummyHash;
    public AuthService(UserRepository u, PasswordEncoder e, JwtService j, AccessService a, LoginAttempts la, ClientOnboardingRepository o) {
        users = u; enc = e; jwt = j; access = a; attempts = la; onboardings = o; dummyHash = e.encode("relleno-para-tiempo-constante");
    }

    /** DTO simple (flag propio del usuario). */
    public static UserDto toDto(AppUser u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), u.isNutritionEnabled(),
                u.isTrainingEnabled(), u.getNutritionistId(), null);
    }

    /**
     * DTO de perfil: para un CLIENT, los servicios indican si tiene responsable de nutrición y de entrenamiento (ADR-013)
     * y onboardingCompleted si ya terminó el asistente de bienvenida (null en el resto de roles).
     */
    @Transactional(readOnly = true)
    public UserDto profile(AppUser u) {
        boolean n = u.isNutritionEnabled(), t = u.isTrainingEnabled();
        Boolean onboarding = null;
        if (u.getRole() == Role.CLIENT) {
            n = access.nutritionResponsibleOf(u) != null;
            t = access.trainingResponsibleOf(u) != null;
            onboarding = onboardings.isCompleted(u.getId());
        }
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), n, t, u.getNutritionistId(), onboarding);
    }

    /** Servicios del profesional (ADR-013 §1): entrenamiento, nutrición o ambos. */
    @Transactional
    public UserDto updateServices(ServicesRequest r) {
        AppUser u = currentUser();
        if (u.getRole() != Role.TRAINER) throw new ForbiddenException("Solo un profesional ofrece servicios");
        if (!r.training() && !r.nutrition()) throw new BadRequestException("Ofrece al menos entrenamiento o nutrición");
        u.setTrainingEnabled(r.training());
        u.setNutritionEnabled(r.nutrition());
        return profile(u);
    }

    /** Acepta email o el usuario "admin". */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest r) {
        String key = r.username().trim().toLowerCase();
        attempts.check(key);
        AppUser u = users.findByEmailIgnoreCase(key).filter(AppUser::isActive).orElse(null);
        boolean ok = enc.matches(r.password(), u == null ? dummyHash : u.getPasswordHash());
        if (u == null || !ok) { attempts.failed(key); throw new BadCredentialsException("x"); }
        attempts.succeeded(key);
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
        return session(u);
    }

    /** Cerrar sesión en el servidor: invalida este token y los de cualquier otro dispositivo. */
    @Transactional
    public void logout() {
        AppUser u = currentUser();
        u.revokeSessions();
    }

    private LoginResponse session(AppUser u) {
        return new LoginResponse(jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()), profile(u));
    }

    private AppUser currentUser() {
        return users.findById(access.current().id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }
}
