package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/dashboard") @Tag(name = "Dashboards")
public class DashboardController {
    private final DashboardService svc;
    public DashboardController(DashboardService s) { svc = s; }

    @GetMapping("/trainer") @PreAuthorize("hasRole('TRAINER')") public TrainerDashboard trainer() { return svc.trainer(); }
    @GetMapping("/client") @PreAuthorize("hasRole('CLIENT')") public ClientDashboard client() { return svc.client(); }
}
