package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.FileDto;
import com.physicalchange.service.FileService;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @DeleteMapping("/api/files/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    /** Sin JWT: la firma y la caducidad de la URL son la autorización. */
    @GetMapping("/api/public/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam long exp, @RequestParam String sig) { return svc.serve(id, exp, sig); }
}
