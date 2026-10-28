package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users; private final PasswordEncoder enc; private final JwtService jwt; private final AccessService access;
    private final LoginAttempts attempts;
    /** Hash de relleno: si el email no existe se compara igual, para que el tiempo de respuesta no revele qué cuentas existen. */
    private final String dummyHash;
    public AuthService(UserRepository u, PasswordEncoder e, JwtService j, AccessService a, LoginAttempts la) {
        users = u; enc = e; jwt = j; access = a; attempts = la; dummyHash = e.encode("relleno-para-tiempo-constante");
    }

    /** DTO simple (flag propio del usuario). */
    public static UserDto toDto(AppUser u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), u.isNutritionEnabled());
    }

    /** DTO de perfil: para un CLIENT, nutritionEnabled refleja si su entrenador ofrece el servicio. */
    @Transactional(readOnly = true)
    public UserDto profile(AppUser u) {
        boolean n = u.isNutritionEnabled();
        if (u.getRole() == Role.CLIENT) {
            n = u.getTrainerId() != null && users.findById(u.getTrainerId()).map(AppUser::isNutritionEnabled).orElse(false);
        }
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getTrainerId(), n);
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
