package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Suscripciones de clientes a planes; cada periodo genera un comprobante pendiente (ADR-004 §4). */
@RestController @RequestMapping("/api/subscriptions") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class SubscriptionController {
    private final SubscriptionService svc;
    public SubscriptionController(SubscriptionService s) { svc = s; }

    @GetMapping public List<SubscriptionDto> list(@RequestParam(required = false) Long clientId, @RequestParam(required = false) String status) {
        return svc.list(clientId, status);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SubscriptionDto create(@Valid @RequestBody SubscriptionRequest r) { return svc.create(r); }
    @PostMapping("/{id}/cancel") public SubscriptionDto cancel(@PathVariable Long id) { return svc.cancel(id); }
}
