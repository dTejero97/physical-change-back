package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ProgramAssignmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Entrenamiento automático al contratar y asignación de programas (docs/api-entrenamiento-automatico.md, T250). */
@RestController @Tag(name = "Entrenamiento")
public class ProgramAssignmentController {
    private final ProgramAssignmentService svc;
    public ProgramAssignmentController(ProgramAssignmentService s) { svc = s; }

    @PostMapping("/api/clients/{clientId}/program-assignments") @ResponseStatus(HttpStatus.CREATED)
    public ProgramAssignmentDto assign(@PathVariable Long clientId, @Valid @RequestBody ProgramAssignmentRequest r) { return svc.assign(clientId, r); }

    @GetMapping("/api/clients/{clientId}/program-assignments")
    public List<ProgramAssignmentDto> list(@PathVariable Long clientId) { return svc.list(clientId); }

    @GetMapping("/api/clients/paid-without-training")
    public List<PaidWithoutTrainingDto> paidWithoutTraining() { return svc.paidWithoutTraining(); }
}
