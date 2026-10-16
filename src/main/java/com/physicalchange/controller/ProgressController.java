package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ExerciseLogService;
import com.physicalchange.service.ProgressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController @RequestMapping("/api/progress") @Tag(name = "Progreso")
public class ProgressController {
    private final ProgressService svc; private final ExerciseLogService logs;
    public ProgressController(ProgressService s, ExerciseLogService e) { svc = s; logs = e; }

    /** Peso y grasa corporal. */
    @GetMapping("/client/{clientId}") public List<ProgressDto> history(@PathVariable Long clientId) { return svc.history(clientId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProgressDto add(@Valid @RequestBody ProgressRequest r) { return svc.add(r); }

    /** Progreso de fuerza por ejercicio y carga por grupo muscular (panel de progresos). */
    @GetMapping("/strength/{clientId}")
    public StrengthReportDto strength(@PathVariable Long clientId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return logs.strength(clientId, from);
    }
}
