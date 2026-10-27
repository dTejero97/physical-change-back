package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Productos de pago único (docs/api-negocio.md). DELETE: 204 si se borra; 200 con el producto archivado si ya tenía ventas. */
@RestController @RequestMapping("/api/products") @Tag(name = "Negocio")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class ProductController {
    private final CatalogService svc;
    public ProductController(CatalogService s) { svc = s; }

    @GetMapping public Page<ProductDto> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean archived, Pageable p) {
        return svc.listProducts(q, archived, p);
    }
    @GetMapping("/{id}") public ProductDto get(@PathVariable Long id) { return svc.getProduct(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProductDto create(@Valid @RequestBody ProductRequest r) { return svc.createProduct(r); }
    @PutMapping("/{id}") public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest r) { return svc.updateProduct(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<ProductDto> delete(@PathVariable Long id) {
        ProductDto archived = svc.deleteProduct(id);
        return archived == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(archived);
    }
}
