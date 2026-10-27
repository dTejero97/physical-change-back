package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.PlatformAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

/** Servicios de la plataforma y sus pagos (docs/api-negocio.md). Solo ADMIN: 403 para el resto. */
@RestController @RequestMapping("/api/platform") @Tag(name = "Negocio")
@PreAuthorize("hasRole('ADMIN')")
public class PlatformController {
    private final PlatformAdminService svc;
    public PlatformController(PlatformAdminService s) { svc = s; }

    @GetMapping("/services") public PlatformServicesDto services() { return svc.list(); }
    @PostMapping("/services") @ResponseStatus(HttpStatus.CREATED) public PlatformServiceDto create(@Valid @RequestBody PlatformServiceRequest r) { return svc.create(r); }
    @PutMapping("/services/{id}") public PlatformServiceDto update(@PathVariable Long id, @Valid @RequestBody PlatformServiceRequest r) { return svc.update(id, r); }
    @DeleteMapping("/services/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }

    @GetMapping("/payments")
    public Page<PlatformPaymentDto> payments(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) Long serviceId, @RequestParam(required = false) String status, Pageable p) {
        return svc.payments(from, to, serviceId, status, p);
    }
    @PostMapping("/payments") @ResponseStatus(HttpStatus.CREATED) public PlatformPaymentDto createPayment(@Valid @RequestBody PlatformPaymentRequest r) { return svc.createPayment(r); }
    @PutMapping("/payments/{id}") public PlatformPaymentDto updatePayment(@PathVariable Long id, @Valid @RequestBody PlatformPaymentRequest r) { return svc.updatePayment(id, r); }
    @DeleteMapping("/payments/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deletePayment(@PathVariable Long id) { svc.deletePayment(id); }
}
