package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.BusinessReportService;
import com.physicalchange.service.BusinessService;
import com.physicalchange.service.ReceiptService.ExportFile;
import com.physicalchange.service.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos de negocio: panel de control e informes (docs/api-negocio.md). Rango por defecto: los últimos 30 días.
 * Finanzas pone antes al día las suscripciones (ADR-004 §4: "al abrir el panel").
 */
@RestController @RequestMapping("/api/business") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class BusinessController {
    private final BusinessService svc;
    private final BusinessReportService reports;
    private final SubscriptionService subscriptions;

    public BusinessController(BusinessService s, BusinessReportService r, SubscriptionService sub) { svc = s; reports = r; subscriptions = sub; }

    @GetMapping("/finance")
    public FinanceDto finance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        subscriptions.catchUpMine();
        return svc.finance(from, to);
    }

    @GetMapping("/finance/day")
    public List<ReceiptDto> financeDay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return svc.financeDay(date); }

    @GetMapping("/finance/by-item")
    public Page<ItemIncomeDto> byItem(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) List<String> kinds, @RequestParam(required = false) String q, Pageable p) {
        return svc.byItem(from, to, kinds, q, p);
    }

    /** Ventas de un plan o producto; para un concepto libre, kind = FREE, refId = 0 y ?name=. */
    @GetMapping("/finance/by-item/{kind}/{refId}")
    public ItemSalesDto itemSales(@PathVariable String kind, @PathVariable Long refId, @RequestParam(required = false) String name,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.itemSales(from, to, kind, refId, name);
    }

    @GetMapping("/clients")
    public ClientsDto clients(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "TOTAL") String series) {
        return svc.clientsData(from, to, series);
    }

    @GetMapping("/clients/day")
    public ClientsDayDto clientsDay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(defaultValue = "TOTAL") String series) {
        return svc.clientsDay(date, series);
    }

    @GetMapping("/clients/states/{state}")
    public List<ClientStateRowDto> clientState(@PathVariable String state) { return svc.clientState(state); }

    @GetMapping("/services")
    public ServicesDto services(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.services(from, to);
    }

    @GetMapping("/reports/{report}")
    public ResponseEntity<byte[]> report(@PathVariable String report, @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ExportFile f = reports.report(report, from, to, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }
}
