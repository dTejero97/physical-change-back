package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ScheduleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/agenda/schedules") @Tag(name = "Agenda")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class ScheduleController {
    private final ScheduleService svc;
    public ScheduleController(ScheduleService s) { svc = s; }

    @GetMapping public List<ScheduleDto> list() { return svc.list(); }
    @GetMapping("/{id}") public ScheduleDetailDto get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ScheduleDto create(@Valid @RequestBody ScheduleRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public ScheduleDto update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest r) { return svc.update(id, r); }
    @PatchMapping("/{id}/active") public ScheduleDto setActive(@PathVariable Long id, @RequestBody ActiveRequest r) { return svc.setActive(id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
}
