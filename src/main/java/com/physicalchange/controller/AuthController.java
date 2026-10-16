package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.service.AuthService;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.exception.NotFoundException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth") @Tag(name = "Auth")
public class AuthController {
    private final AuthService auth; private final AccessService access; private final UserRepository users;
    public AuthController(AuthService a, AccessService ac, UserRepository u) { auth = a; access = ac; users = u; }

    @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r); }

    @GetMapping("/me") public UserDto me() {
        return auth.profile(users.findById(access.current().id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado")));
    }

    /** Configuración: cambiar el nombre visible. */
    @PutMapping("/me") public UserDto updateMe(@Valid @RequestBody UpdateProfileRequest r) { return auth.updateProfile(r); }

    /** Configuración: cambiar la contraseña (requiere la actual). */
    @PutMapping("/password") public void changePassword(@Valid @RequestBody ChangePasswordRequest r) { auth.changePassword(r); }
}
