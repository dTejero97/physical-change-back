package com.physicalchange.controller;

import com.physicalchange.service.ReceiptService.ExportFile;
import com.physicalchange.service.audit.AuditQueryService;
import com.physicalchange.service.audit.AuditQueryService.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/** Registro de auditoría: solo lectura y solo ADMIN (ADR-016, docs/api-auditoria.md). */
@RestController @RequestMapping("/api/admin/audit") @Tag(name = "Administración")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditQueryService svc;
    public AuditController(AuditQueryService s) { svc = s; }

    @GetMapping
    public Page<AuditEventDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) Long actorId, @RequestParam(required = false) Long clientId,
                                    @RequestParam(required = false) List<String> action, @RequestParam(required = false) String q, Pageable p) {
        return svc.search(new Filter(from, to, actorId, clientId, action, q), p);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long actorId, @RequestParam(required = false) Long clientId,
                                         @RequestParam(required = false) List<String> action, @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) return ResponseEntity.badRequest().build();
        ExportFile f = svc.export(new Filter(from, to, actorId, clientId, action, q));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    @GetMapping("/actions") public List<AuditActionDto> actions() { return svc.actions(); }
}
