package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/payments") @Tag(name = "Pagos")
public class PaymentController {
    private final PaymentService svc;
    public PaymentController(PaymentService s) { svc = s; }

    /** Entrenador: sus cobros. Cliente: sus pagos. */
    @GetMapping public Page<PaymentDto> list(Pageable p) { return svc.list(p); }

    @PostMapping @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public PaymentDto create(@Valid @RequestBody PaymentRequest r) { return svc.create(r); }

    @PostMapping("/{id}/paid") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public PaymentDto markPaid(@PathVariable Long id) { return svc.markPaid(id); }

    @DeleteMapping("/{id}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }
}
