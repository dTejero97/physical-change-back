package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.WorkoutService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name = "Entrenamientos")
public class WorkoutController {
    private final WorkoutService svc; private final com.physicalchange.security.AccessService access;
    public WorkoutController(WorkoutService s, com.physicalchange.security.AccessService a) { svc = s; access = a; }

    // Gestión (TRAINER/ADMIN, ver SecurityConfig)
    @GetMapping("/api/workouts") public Page<WorkoutDto> list(Pageable p) { return svc.list(p); }
    @PostMapping("/api/workouts") @ResponseStatus(HttpStatus.CREATED) public WorkoutDto create(@Valid @RequestBody WorkoutRequest r) { return svc.create(r); }
    @PostMapping("/api/workouts/assign") @ResponseStatus(HttpStatus.CREATED) public AssignmentDto assign(@Valid @RequestBody AssignRequest r) { return svc.assign(r); }

    // Cliente
    @GetMapping("/api/my/assignments") public Page<AssignmentDto> mine(Pageable p) { return svc.forClient(access.current().id(), p); }
    @PostMapping("/api/assignments/{id}/complete") public AssignmentDto complete(@PathVariable Long id) { return svc.complete(id); }
}
