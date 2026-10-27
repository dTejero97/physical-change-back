package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ReceiptService;
import com.physicalchange.service.SubscriptionService;
import com.physicalchange.service.ReceiptService.ExportFile;
import com.physicalchange.service.ReceiptService.ReceiptFilter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Comprobantes de pago (docs/api-negocio.md). No hay PUT ni DELETE: un comprobante solo se paga o se anula.
 * Antes de listar, KPIs y exportar se ponen al día las suscripciones (ADR-004 §4), en su propia transacción.
 */
@RestController @RequestMapping("/api/receipts") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class ReceiptController {
    private final ReceiptService svc;
    private final SubscriptionService subscriptions;
    public ReceiptController(ReceiptService s, SubscriptionService sub) { svc = s; subscriptions = sub; }

    @GetMapping
    public Page<ReceiptDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String q, @RequestParam(required = false) List<Long> clientIds,
                                 @RequestParam(required = false) List<String> lineKinds, @RequestParam(required = false) List<String> statuses,
                                 @RequestParam(required = false) List<String> methods, @RequestParam(required = false) BigDecimal minAmount,
                                 @RequestParam(required = false) BigDecimal maxAmount, Pageable p) {
        subscriptions.catchUpMine();
        return svc.list(new ReceiptFilter(from, to, q, clientIds, lineKinds, statuses, methods, minAmount, maxAmount), p);
    }

    @GetMapping("/summary")
    public ReceiptSummaryDto summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        subscriptions.catchUpMine();
        return svc.summary(from, to);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String q, @RequestParam(required = false) List<Long> clientIds,
                                         @RequestParam(required = false) List<String> lineKinds, @RequestParam(required = false) List<String> statuses,
                                         @RequestParam(required = false) List<String> methods, @RequestParam(required = false) BigDecimal minAmount,
                                         @RequestParam(required = false) BigDecimal maxAmount) {
        subscriptions.catchUpMine();
        ExportFile f = svc.export(new ReceiptFilter(from, to, q, clientIds, lineKinds, statuses, methods, minAmount, maxAmount), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    /** Solo ADMIN (el servicio lo comprueba y responde 403 al resto). */
    @GetMapping("/movements")
    public Page<MovementDto> movements(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String q, @RequestParam(required = false) List<String> types,
                                       @RequestParam(required = false) BigDecimal minAmount, @RequestParam(required = false) BigDecimal maxAmount,
                                       Pageable p) {
        return svc.movements(from, to, q, types, minAmount, maxAmount, p);
    }

    @GetMapping("/{id}") public ReceiptDto get(@PathVariable Long id) { return svc.get(id); }

    /** Calcula los totales sin guardar (paso "Previsualizar"). */
    @PostMapping("/preview") public ReceiptDto preview(@Valid @RequestBody ReceiptRequest r) { return svc.preview(r); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ReceiptDto create(@Valid @RequestBody ReceiptRequest r) { return svc.create(r); }

    @PostMapping("/{id}/pay") public ReceiptDto pay(@PathVariable Long id, @Valid @RequestBody(required = false) PayReceiptRequest r) { return svc.pay(id, r); }

    @PostMapping("/{id}/void") public ReceiptDto voidReceipt(@PathVariable Long id, @Valid @RequestBody VoidReceiptRequest r) { return svc.voidReceipt(id, r.reason()); }
}
