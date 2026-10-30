package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.DailyLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

/** Diario del cliente: peso, sueño, ánimo y descanso por día, y resumen del mes (docs/api-cliente-seguimiento.md, T201). */
@RestController @Tag(name = "Seguimiento diario")
public class DailyLogController {
    private final DailyLogService svc;
    public DailyLogController(DailyLogService s) { svc = s; }

    // ---- Cliente (siempre el del token) ----
    @GetMapping("/api/me/daily-log/{date}") @PreAuthorize("hasRole('CLIENT')")
    public DailyLogDto mine(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return svc.mine(date); }

    @PutMapping("/api/me/daily-log/{date}") @PreAuthorize("hasRole('CLIENT')")
    public DailyLogDto save(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @Valid @RequestBody DailyLogRequest r) {
        return svc.save(date, r);
    }

    @GetMapping("/api/me/daily-log") @PreAuthorize("hasRole('CLIENT')")
    public DailyLogMonthDto myMonth(@RequestParam String month) { return svc.myMonth(month); }

    // ---- Profesional / ADMIN (solo lectura) ----
    @GetMapping("/api/clients/{clientId}/daily-log/{date}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public DailyLogDto forClient(@PathVariable Long clientId, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return svc.forClient(clientId, date);
    }

    @GetMapping("/api/clients/{clientId}/daily-log") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public DailyLogMonthDto monthForClient(@PathVariable Long clientId, @RequestParam String month) { return svc.monthForClient(clientId, month); }
}
