package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.TrainerReportDto;
import com.physicalchange.service.ReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reports") @Tag(name = "Informes")
public class ReportController {
    private final ReportService svc;
    public ReportController(ReportService s) { svc = s; }

    @GetMapping("/trainer") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public TrainerReportDto trainer() { return svc.trainer(); }
}
