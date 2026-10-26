package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.FileDto;
import com.physicalchange.dto.Dtos.UploadDto;
import com.physicalchange.dto.Dtos.UploadRequest;
import com.physicalchange.service.FileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Subida de archivos (ADR-001) y descarga pública con URL firmada. */
@RestController @Tag(name = "Archivos")
public class FileController {
    private final FileService svc;
    public FileController(FileService s) { svc = s; }

    @PostMapping(path = "/api/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public FileDto upload(@RequestPart("file") MultipartFile file, @RequestParam String purpose) { return svc.upload(file, purpose); }

    /** Subida directa a Cloudflare R2 (ADR-005): devuelve la URL PUT prefirmada. */
    @PostMapping("/api/files/uploads") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public UploadDto createUpload(@Valid @RequestBody UploadRequest r) { return svc.createUpload(r); }

    @PostMapping("/api/files/{id}/complete") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public FileDto complete(@PathVariable Long id) { return svc.complete(id); }

    @DeleteMapping("/api/files/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    /** Sin JWT: la firma y la caducidad de la URL son la autorización. */
    @GetMapping("/api/public/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam long exp, @RequestParam String sig) { return svc.serve(id, exp, sig); }
}
