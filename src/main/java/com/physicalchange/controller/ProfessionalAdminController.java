package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ProfessionalAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Alta y gestión de profesionales: solo el ADMIN (T228, docs/api-profesionales.md). /api/admin/** ya exige ADMIN en SecurityConfig. */
@RestController @RequestMapping("/api/admin/professionals") @Tag(name = "Administración")
@PreAuthorize("hasRole('ADMIN')")
public class ProfessionalAdminController {
    private final ProfessionalAdminService svc;
    public ProfessionalAdminController(ProfessionalAdminService s) { svc = s; }

    @GetMapping public List<ProfessionalAdminDto> list() { return svc.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProfessionalAdminDto create(@Valid @RequestBody ProfessionalCreateRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public ProfessionalAdminDto update(@PathVariable Long id, @Valid @RequestBody ProfessionalUpdateRequest r) { return svc.update(id, r); }
    @PutMapping("/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@PathVariable Long id, @Valid @RequestBody ProfessionalPasswordRequest r) { svc.setPassword(id, r); }
}
