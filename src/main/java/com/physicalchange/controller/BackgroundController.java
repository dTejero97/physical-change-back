package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.BackgroundService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Fondos de la página de carga (docs/api-fondos.md, T208): galería para ADMIN y TRAINER y lectura pública del fondo activo. */
@RestController @Tag(name = "Fondos de la página de carga")
public class BackgroundController {
    private final BackgroundService svc;
    public BackgroundController(BackgroundService s) { svc = s; }

    @GetMapping("/api/backgrounds") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public BackgroundGalleryDto gallery() { return svc.gallery(); }

    @PostMapping(path = "/api/backgrounds", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public BackgroundDto upload(@RequestPart("file") MultipartFile file) { return svc.upload(file); }

    @PutMapping("/api/backgrounds/active") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public BackgroundGalleryDto setActive(@Valid @RequestBody SetActiveBackgroundRequest r) { return svc.setActive(r.backgroundId()); }

    @DeleteMapping("/api/backgrounds/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    // ---------- Público, sin JWT ----------

    @GetMapping("/api/public/backgrounds/active")
    public ResponseEntity<PublicBackgroundDto> active(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String inm) {
        PublicBackgroundDto dto = svc.publicActive();
        String etag = "\"active-" + dto.id() + "\"";
        ResponseEntity.BodyBuilder b = ResponseEntity.status(etag.equals(inm) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(etag).header(HttpHeaders.CACHE_CONTROL, "public, max-age=60");
        return etag.equals(inm) ? b.build() : b.body(dto);
    }

    @GetMapping("/api/public/backgrounds/active/{name}")
    public ResponseEntity<byte[]> activeImage(@PathVariable String name,
                                              @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String inm) {
        return svc.activeImage(name, inm);
    }

    @GetMapping("/api/public/backgrounds/{id}/{name}")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable String name,
                                        @RequestParam(required = false) Long exp, @RequestParam(required = false) String sig,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String inm) {
        return svc.image(id, name, exp, sig, inm);
    }
}
