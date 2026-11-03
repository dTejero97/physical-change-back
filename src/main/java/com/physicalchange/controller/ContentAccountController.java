package com.physicalchange.controller;

import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.service.content.SocialAccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Cuentas sociales y OAuth (docs/api-contenido.md §1). Ver: también el colaborador con permiso; gestionar: solo ADMIN y TRAINER. */
@RestController @RequestMapping("/api/content") @Tag(name = "Contenido")
public class ContentAccountController {
    private final SocialAccountService svc;
    public ContentAccountController(SocialAccountService s) { svc = s; }

    @GetMapping("/accounts") public List<SocialAccountDto> list() { return svc.list(); }

    @PostMapping("/accounts/{network}/connect") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public ConnectResponse connect(@PathVariable String network) { return svc.connect(network); }

    @PostMapping("/oauth/callback") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public SocialAccountDto callback(@Valid @RequestBody OAuthCallbackRequest r) { return svc.callback(r); }

    @PostMapping("/accounts/{network}/disable") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public SocialAccountDto disable(@PathVariable String network) { return svc.disable(network); }

    @PostMapping("/accounts/{network}/enable") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public SocialAccountDto enable(@PathVariable String network) { return svc.enable(network); }

    @PostMapping("/accounts/{network}/sync") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public SocialAccountDto sync(@PathVariable String network) { return svc.sync(network); }

    @DeleteMapping("/accounts/{network}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String network) { svc.disconnect(network); }
}
