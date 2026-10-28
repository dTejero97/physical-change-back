package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.TeamService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Alta del colaborador desde su correo (pública, sin JWT). Token de 256 bits en base64url. */
@RestController @RequestMapping("/api/public/team-invitations") @Tag(name = "Público: equipo") @Validated
public class PublicTeamInvitationController {
    private static final String TOKEN = "[A-Za-z0-9_-]{43}";
    private final TeamService svc;
    public PublicTeamInvitationController(TeamService s) { svc = s; }

    @GetMapping("/{token}") public PublicTeamInvitationDto info(@PathVariable @Pattern(regexp = TOKEN) String token) { return svc.publicInfo(token); }

    @PostMapping("/{token}/accept") @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse accept(@PathVariable @Pattern(regexp = TOKEN) String token, @Valid @RequestBody TeamAcceptRequest r) {
        return svc.accept(token, r);
    }
}
