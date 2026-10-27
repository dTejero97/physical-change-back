package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.CouponService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/coupons") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class CouponController {
    private final CouponService svc;
    public CouponController(CouponService s) { svc = s; }

    @GetMapping public Page<CouponDto> list(@RequestParam(required = false) String q, Pageable p) { return svc.list(q, p); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CouponDto create(@Valid @RequestBody CouponRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public CouponDto update(@PathVariable Long id, @Valid @RequestBody CouponRequest r) { return svc.update(id, r); }
    /** 409 si ya se ha usado: hay que desactivarlo. */
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
}
