package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.AppointmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/appointments") @Tag(name = "Citas y reservas")
public class AppointmentController {
    private final AppointmentService svc;
    public AppointmentController(AppointmentService s) { svc = s; }

    @GetMapping public Page<AppointmentDto> mine(Pageable p) { return svc.mine(p); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public AppointmentDto book(@Valid @RequestBody AppointmentRequest r) { return svc.book(r); }
    @DeleteMapping("/{id}") public AppointmentDto cancel(@PathVariable Long id) { return svc.cancel(id); }
}
