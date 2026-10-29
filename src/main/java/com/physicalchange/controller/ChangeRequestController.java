package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ChangeRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Solicitudes de cambio del cliente (docs/api-solicitudes-logros.md). El servicio vuelve a comprobar cada permiso. */
@RestController @Tag(name = "Solicitudes de cambio")
public class ChangeRequestController {
    private final ChangeRequestService svc;
    public ChangeRequestController(ChangeRequestService s) { svc = s; }

    // ---- Cliente ----
    @PostMapping("/api/change-requests/training") @PreAuthorize("hasRole('CLIENT')") @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestDto training(@Valid @RequestBody TrainingChangeRequest r) { return svc.createTraining(r); }
    @PostMapping("/api/change-requests/nutrition") @PreAuthorize("hasRole('CLIENT')") @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestDto nutrition(@Valid @RequestBody NutritionChangeRequest r) { return svc.createNutrition(r); }
    @GetMapping("/api/change-requests/mine") @PreAuthorize("hasRole('CLIENT')")
    public List<ChangeRequestDto> mine(@RequestParam(required = false) String status) { return svc.mine(status); }

    // ---- Profesional ----
    @GetMapping("/api/change-requests") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public List<ChangeRequestDto> inbox(@RequestParam(required = false) String status) { return svc.inbox(status); }
    @GetMapping("/api/clients/{clientId}/change-requests")
    public List<ChangeRequestDto> forClient(@PathVariable Long clientId, @RequestParam(required = false) String status) { return svc.forClient(clientId, status); }
    @PostMapping("/api/change-requests/{id}/accept") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public ChangeRequestDto accept(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest d) { return svc.accept(id, d); }
    @PostMapping("/api/change-requests/{id}/reject") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public ChangeRequestDto reject(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest d) { return svc.reject(id, d); }

    // ---- Ambos ----
    @GetMapping("/api/change-requests/{id}/history")
    public List<ChangeRequestEventDto> history(@PathVariable Long id) { return svc.history(id); }
}
