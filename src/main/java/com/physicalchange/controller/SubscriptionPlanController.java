package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Planes de suscripción: mismas reglas que los productos (docs/api-negocio.md). */
@RestController @RequestMapping("/api/subscription-plans") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class SubscriptionPlanController {
    private final CatalogService svc;
    public SubscriptionPlanController(CatalogService s) { svc = s; }

    @GetMapping public Page<SubscriptionPlanDto> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean archived, Pageable p) {
        return svc.listPlans(q, archived, p);
    }
    @GetMapping("/{id}") public SubscriptionPlanDto get(@PathVariable Long id) { return svc.getPlan(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SubscriptionPlanDto create(@Valid @RequestBody SubscriptionPlanRequest r) { return svc.createPlan(r); }
    @PutMapping("/{id}") public SubscriptionPlanDto update(@PathVariable Long id, @Valid @RequestBody SubscriptionPlanRequest r) { return svc.updatePlan(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<SubscriptionPlanDto> delete(@PathVariable Long id) {
        SubscriptionPlanDto archived = svc.deletePlan(id);
        return archived == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(archived);
    }
}
