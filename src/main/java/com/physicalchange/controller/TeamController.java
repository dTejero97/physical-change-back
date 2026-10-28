package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.TeamService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Equipo del profesional (ADR-011, docs/api-equipo.md). /api/team/me es del colaborador; el resto, del profesional. */
@RestController @RequestMapping("/api/team") @Tag(name = "Equipo")
public class TeamController {
    private final TeamService svc;
    public TeamController(TeamService s) { svc = s; }

    @GetMapping("/me") @PreAuthorize("hasRole('COLLABORATOR')") public TeamMeDto me() { return svc.me(); }

    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") public List<TeamMemberDto> list() { return svc.list(); }
    @PostMapping("/invitations") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public TeamMemberDto invite(@Valid @RequestBody TeamInviteRequest r) { return svc.invite(r); }
    @PostMapping("/{id}/resend") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") public TeamMemberDto resend(@PathVariable Long id) { return svc.resend(id); }
    @PutMapping("/{id}/permissions") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public TeamMemberDto permissions(@PathVariable Long id, @Valid @RequestBody TeamPermissionsDto r) { return svc.permissions(id, r); }
    @PutMapping("/{id}/clients") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public TeamMemberDto clients(@PathVariable Long id, @Valid @RequestBody TeamClientsRequest r) { return svc.clients(id, r); }
    @DeleteMapping("/{id}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) { svc.remove(id); }
}
