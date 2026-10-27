package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.ClientBonusDto;
import com.physicalchange.service.BonusService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Bonos de sesiones: el profesional ve los de su cliente; el cliente, sus bonos vigentes. */
@RestController @Tag(name = "Negocio")
public class BonusController {
    private final BonusService svc;
    public BonusController(BonusService s) { svc = s; }

    @GetMapping("/api/clients/{id}/bonuses") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public List<ClientBonusDto> ofClient(@PathVariable Long id) { return svc.ofClient(id); }

    @GetMapping("/api/me/bonuses") @PreAuthorize("hasRole('CLIENT')")
    public List<ClientBonusDto> mine() { return svc.mine(); }
}
