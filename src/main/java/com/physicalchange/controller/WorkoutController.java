package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.service.WorkoutService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name = "Rutinas y asignaciones")
public class WorkoutController {
    private final WorkoutService svc; private final AccessService access;
    public WorkoutController(WorkoutService s, AccessService a) { svc = s; access = a; }

    // ---- Rutinas (TRAINER/ADMIN, ver SecurityConfig) ----
    @GetMapping("/api/workouts") public Page<WorkoutDto> list(Pageable p) { return svc.list(p); }
    @PostMapping("/api/workouts") @ResponseStatus(HttpStatus.CREATED) public WorkoutDto create(@Valid @RequestBody WorkoutRequest r) { return svc.create(r); }
    @GetMapping("/api/workouts/{id}") public WorkoutDetailDto get(@PathVariable Long id) { return svc.get(id); }
    @PutMapping("/api/workouts/{id}") public WorkoutDto update(@PathVariable Long id, @Valid @RequestBody WorkoutRequest r) { return svc.update(id, r); }
    @DeleteMapping("/api/workouts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }

    // ---- Ejercicios configurados dentro de la rutina: series, repeticiones, peso ----
    @PostMapping("/api/workouts/{id}/items") @ResponseStatus(HttpStatus.CREATED)
    public WorkoutDetailDto addItem(@PathVariable Long id, @Valid @RequestBody WorkoutItemRequest r) { return svc.addItem(id, r); }
    @PutMapping("/api/workouts/{id}/items/{itemId}")
    public WorkoutDetailDto updateItem(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody WorkoutItemRequest r) { return svc.updateItem(id, itemId, r); }
    @DeleteMapping("/api/workouts/{id}/items/{itemId}")
    public WorkoutDetailDto removeItem(@PathVariable Long id, @PathVariable Long itemId) { return svc.removeItem(id, itemId); }

    // ---- Asignación ----
    @PostMapping("/api/workouts/assign") @ResponseStatus(HttpStatus.CREATED) public AssignmentDto assign(@Valid @RequestBody AssignRequest r) { return svc.assign(r); }

    // ---- Cliente ----
    @GetMapping("/api/my/assignments") public Page<AssignmentDto> mine(Pageable p) { return svc.forClient(access.current().id(), p); }
    @PostMapping("/api/assignments/{id}/complete") public AssignmentDto complete(@PathVariable Long id) { return svc.complete(id); }

    /** Planificado vs. realizado. Lo ven el cliente asignado, su entrenador y el admin. */
    @GetMapping("/api/assignments/{id}/compliance") public ComplianceDto compliance(@PathVariable Long id) { return svc.compliance(id); }
}
