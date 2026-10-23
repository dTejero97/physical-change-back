package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.SessionTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/agenda/session-types") @Tag(name = "Agenda")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class SessionTypeController {
    private final SessionTypeService svc;
    public SessionTypeController(SessionTypeService s) { svc = s; }

    @GetMapping public List<SessionTypeDto> list() { return svc.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SessionTypeDto create(@Valid @RequestBody SessionTypeRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public SessionTypeDto update(@PathVariable Long id, @Valid @RequestBody SessionTypeRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
    /** Pasa sus eventos (desde hoy) y horarios a otro tipo. */
    @PostMapping("/{id}/replace") public ReplaceResultDto replace(@PathVariable Long id, @Valid @RequestBody ReplaceSessionTypeRequest r) { return svc.replace(id, r); }
}
