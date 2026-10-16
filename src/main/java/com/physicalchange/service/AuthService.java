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
    public AuthService(UserRepository u, PasswordEncoder e, JwtService j, AccessService a) { users = u; enc = e; jwt = j; access = a; }

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
        AppUser u = users.findByEmailIgnoreCase(r.username().trim()).filter(AppUser::isActive)
                .orElseThrow(() -> new BadCredentialsException("x"));
        if (!enc.matches(r.password(), u.getPasswordHash())) throw new BadCredentialsException("x");
        return new LoginResponse(jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())), profile(u));
    }

    @Transactional
    public UserDto updateProfile(UpdateProfileRequest r) {
        AppUser u = currentUser();
        u.setFullName(r.fullName());
        return profile(u);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest r) {
        AppUser u = currentUser();
        if (!enc.matches(r.currentPassword(), u.getPasswordHash())) throw new ForbiddenException("La contraseña actual no es correcta");
        u.setPasswordHash(enc.encode(r.newPassword()));
    }

    private AppUser currentUser() {
        return users.findById(access.current().id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }
}
