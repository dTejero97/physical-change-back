package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/clients") @Tag(name = "Clientes (TRAINER/ADMIN)")
public class ClientController {
    private final ClientService svc; private final WorkoutService workouts; private final ClientProfileService profiles;
    public ClientController(ClientService s, WorkoutService w, ClientProfileService p) { svc = s; workouts = w; profiles = p; }

    @GetMapping public Page<UserDto> list(Pageable p) { return svc.list(p); }
    /** Fichas con etiquetas y métricas calculadas (activos y archivados). Ver docs/api-clientes.md. */
    @GetMapping("/profiles") public List<ClientProfileDto> profiles() { return profiles.list(); }
    @GetMapping("/{id}") public UserDto get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public UserDto create(@Valid @RequestBody CreateClientRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateClientRequest r) { return svc.update(id, r); }
    @PutMapping("/{id}/profile") public ClientProfileDto updateProfile(@PathVariable Long id, @Valid @RequestBody ClientProfileRequest r) { return profiles.update(id, r); }
    /** Borrado lógico: solo clientes archivados. */
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void remove(@PathVariable Long id) { profiles.remove(id); }
    @GetMapping("/{id}/assignments") public Page<AssignmentDto> assignments(@PathVariable Long id, Pageable p) { return workouts.forClient(id, p); }
}
