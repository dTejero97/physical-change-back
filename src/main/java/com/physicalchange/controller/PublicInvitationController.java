package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.InvitationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Alta del cliente desde el enlace de invitación. Sin sesión: ruta permitida en SecurityConfig (/api/public/**). */
@RestController @RequestMapping("/api/public/invitations") @Tag(name = "Público: invitaciones") @Validated
public class PublicInvitationController {
    /** Formato del token (base64url de 32 bytes): evita consultas con valores arbitrarios. */
    private static final String TOKEN = "[A-Za-z0-9_-]{20,64}";
    private final InvitationService svc;
    public PublicInvitationController(InvitationService s) { svc = s; }

    @GetMapping("/{token}")
    public PublicInvitationDto info(@PathVariable @Pattern(regexp = TOKEN) String token) { return svc.publicInfo(token); }

    @PostMapping("/{token}/accept") @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse accept(@PathVariable @Pattern(regexp = TOKEN) String token, @Valid @RequestBody AcceptInvitationRequest r) {
        return svc.accept(token, r);
    }
}
