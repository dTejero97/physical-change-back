package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ExerciseLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController @RequestMapping("/api/exercise-logs") @Tag(name = "Registro de series")
public class ExerciseLogController {
    private final ExerciseLogService svc;
    public ExerciseLogController(ExerciseLogService s) { svc = s; }

    /** Cliente: registra lo que ha levantado. Entrenador: lo registra para un cliente suyo. */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ExerciseLogDto log(@Valid @RequestBody ExerciseLogRequest r) { return svc.log(r); }

    @GetMapping("/client/{clientId}")
    public List<ExerciseLogDto> history(@PathVariable Long clientId,
                                        @RequestParam(required = false) Long exerciseId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return svc.history(clientId, exerciseId, from);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }
}
