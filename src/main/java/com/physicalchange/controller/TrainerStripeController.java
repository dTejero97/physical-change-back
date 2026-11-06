package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.service.TrainerStripeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/**
 * Cuenta Stripe Connect del profesional (ADR-018, docs/api-stripe-profesional.md). Las rutas del profesional exigen sesión;
 * la vuelta desde Stripe es pública y se identifica por el state de un solo uso.
 */
@RestController @Tag(name = "Pagos online · cuenta del profesional")
public class TrainerStripeController {
    private final TrainerStripeService svc;
    private final AccessService access;

    public TrainerStripeController(TrainerStripeService s, AccessService a) { svc = s; access = a; }

    @GetMapping("/api/payments/stripe/account") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
    public StripeAccountDto status() { return svc.status(access.current().id()); }

    @PostMapping("/api/payments/stripe/account/link") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')") @ResponseStatus(HttpStatus.CREATED)
    public StripeLinkDto link(@RequestBody(required = false) StripeLinkRequest body) {
        return svc.link(access.current().id(), body == null ? null : body.accountType());
    }

    @PostMapping("/api/payments/stripe/account/refresh") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
    public StripeAccountDto refresh() { return svc.refresh(access.current().id()); }

    @DeleteMapping("/api/payments/stripe/account") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink() { svc.unlink(access.current().id()); }

    /** Vuelta desde Stripe: siempre redirige a la pantalla de Negocio (nunca JSON). */
    @GetMapping("/api/public/stripe/connect/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(svc.callback(code, state, error))).build();
    }
}
