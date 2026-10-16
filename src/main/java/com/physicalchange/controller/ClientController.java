package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/clients") @Tag(name = "Clientes (TRAINER/ADMIN)")
public class ClientController {
    private final ClientService svc; private final WorkoutService workouts;
    public ClientController(ClientService s, WorkoutService w) { svc = s; workouts = w; }

    @GetMapping public Page<UserDto> list(Pageable p) { return svc.list(p); }
    @GetMapping("/{id}") public UserDto get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public UserDto create(@Valid @RequestBody CreateClientRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateClientRequest r) { return svc.update(id, r); }
    @GetMapping("/{id}/assignments") public Page<AssignmentDto> assignments(@PathVariable Long id, Pageable p) { return workouts.forClient(id, p); }
}
