package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.ActivityDto;
import com.physicalchange.service.AgendaActivityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/agenda/activity") @Tag(name = "Agenda")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class AgendaActivityController {
    private final AgendaActivityService svc;
    public AgendaActivityController(AgendaActivityService s) { svc = s; }

    /** profile: ALL | CLIENTS | TEAM; actorIds: clientes o colaboradores concretos (vacío = todos). */
    @GetMapping
    public List<ActivityDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "ALL") String profile,
                                  @RequestParam(required = false) Set<Long> actorIds) {
        return svc.list(from, to, profile, actorIds == null ? Set.of() : actorIds);
    }
}
