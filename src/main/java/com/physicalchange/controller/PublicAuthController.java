package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.PasswordResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Recuperación de contraseña sin sesión (T137). Ruta pública: la limita RateLimitFilter por IP y el servicio por email. */
@RestController @RequestMapping("/api/public/auth") @Tag(name = "Público: contraseña")
public class PublicAuthController {
    private final PasswordResetService svc;
    public PublicAuthController(PasswordResetService s) { svc = s; }

    /** Siempre 204, exista o no la cuenta. */
    @PostMapping("/forgot-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@Valid @RequestBody ForgotPasswordRequest r) { svc.forgot(r.email()); }

    @PostMapping("/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest r) { svc.reset(r.token(), r.newPassword()); }
}
