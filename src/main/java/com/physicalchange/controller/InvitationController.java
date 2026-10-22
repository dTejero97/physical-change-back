package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.InvitationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/invitations") @Tag(name = "Invitaciones de clientes")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class InvitationController {
    private final InvitationService svc;
    public InvitationController(InvitationService s) { svc = s; }

    @GetMapping public List<InvitationDto> list() { return svc.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public InvitationDto create(@Valid @RequestBody InvitationRequest r) { return svc.create(r); }
    @PostMapping("/link") @ResponseStatus(HttpStatus.CREATED)
    public InvitationDto link(@RequestBody(required = false) InvitationLinkRequest r) { return svc.createLink(r); }
    @PostMapping("/{id}/resend") public InvitationDto resend(@PathVariable Long id) { return svc.resend(id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
}
