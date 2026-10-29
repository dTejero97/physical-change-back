package com.physicalchange.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.OnboardingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Asistente de bienvenida del cliente (docs/api-onboarding.md). El PUT se recibe como JSON sin tipar para distinguir
 * un campo ausente (no se toca) de uno a null (se vacía); el servicio valida cada campo.
 */
@RestController @Tag(name = "Asistente de bienvenida")
public class OnboardingController {
    private final OnboardingService svc;
    public OnboardingController(OnboardingService s) { svc = s; }

    // ---- Cliente (siempre el del token) ----
    @GetMapping("/api/me/onboarding") @PreAuthorize("hasRole('CLIENT')")
    public OnboardingDto mine() { return svc.mine(); }
    @PutMapping("/api/me/onboarding") @PreAuthorize("hasRole('CLIENT')")
    public OnboardingDto update(@RequestBody JsonNode body) { return svc.update(body); }
    @PostMapping("/api/me/onboarding/complete") @PreAuthorize("hasRole('CLIENT')")
    public OnboardingDto complete() { return svc.complete(); }

    // ---- Profesional / ADMIN (solo lectura) ----
    @GetMapping("/api/clients/{clientId}/onboarding") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public OnboardingDto forClient(@PathVariable Long clientId) { return svc.forClient(clientId); }
}
