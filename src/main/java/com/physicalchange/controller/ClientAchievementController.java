package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.ClientAchievementsDto;
import com.physicalchange.security.AccessService;
import com.physicalchange.service.AchievementProgressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Logros, puntos, nivel y estadísticas de un cliente (docs/api-solicitudes-logros.md §4, ADR-012 §5). */
@RestController @Tag(name = "Logros del cliente")
public class ClientAchievementController {
    private final AchievementProgressService svc; private final AccessService access;
    public ClientAchievementController(AchievementProgressService s, AccessService a) { svc = s; access = a; }

    @GetMapping("/api/my/achievements") @PreAuthorize("hasRole('CLIENT')")
    public ClientAchievementsDto mine() { return svc.summary(access.current().id()); }

    /** TRAINER (sus clientes) y ADMIN, por SecurityConfig (/api/clients/**) y assertCanAccessClient. */
    @GetMapping("/api/clients/{clientId}/achievements")
    public ClientAchievementsDto forClient(@PathVariable Long clientId) { return svc.summary(clientId); }
}
