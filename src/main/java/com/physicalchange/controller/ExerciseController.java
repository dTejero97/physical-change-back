package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ExerciseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/exercises") @Tag(name = "Ejercicios")
public class ExerciseController {
    private final ExerciseService svc;
    public ExerciseController(ExerciseService s) { svc = s; }

    @GetMapping public Page<ExerciseDto> search(@RequestParam(required = false) String q, @RequestParam(required = false) String muscle, Pageable p) { return svc.search(q, muscle, p); }
    @PostMapping @PreAuthorize("hasAnyRole('ADMIN','TRAINER','COLLABORATOR')") @ResponseStatus(HttpStatus.CREATED)
    public ExerciseDto create(@Valid @RequestBody ExerciseRequest r) { return svc.create(r); }
    @DeleteMapping("/{id}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }
}
