package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.LeadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/leads") @Tag(name = "Leads")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class LeadController {
    private final LeadService svc;
    public LeadController(LeadService s) { svc = s; }

    @GetMapping public List<LeadDto> list() { return svc.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public LeadDto create(@Valid @RequestBody LeadRequest r) { return svc.create(r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
    @PostMapping("/{id}/invite") @ResponseStatus(HttpStatus.CREATED) public InvitationDto invite(@PathVariable Long id) { return svc.invite(id); }
}
