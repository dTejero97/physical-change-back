package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.AgendaEventService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/**
 * Sesiones que el cliente puede reservar (docs/api-cliente-seguimiento.md, T201 D17). Reservar y cancelar siguen en
 * POST /api/agenda/events/{id}/bookings y PATCH /api/agenda/bookings/{id} (docs/api-agenda.md).
 */
@RestController @Tag(name = "Seguimiento diario")
public class ClientAgendaController {
    private final AgendaEventService svc;
    public ClientAgendaController(AgendaEventService s) { svc = s; }

    @GetMapping("/api/me/agenda/available") @PreAuthorize("hasRole('CLIENT')")
    public List<AvailableSessionDto> available(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.availableForClient(from, to);
    }
}
