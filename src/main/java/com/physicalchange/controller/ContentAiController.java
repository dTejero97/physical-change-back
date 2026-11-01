package com.physicalchange.controller;

import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.service.ai.ContentAiService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Generar ideas, ganchos y copys con Physical AI (docs/api-contenido.md §5). El colaborador no usa Physical AI (ADR-011 §6). */
@RestController @RequestMapping("/api/content/ai") @Tag(name = "Contenido")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class ContentAiController {
    private final ContentAiService svc;
    public ContentAiController(ContentAiService s) { svc = s; }

    @PostMapping("/ideas") @ResponseStatus(HttpStatus.CREATED) public ContentAiResultDto ideas(@Valid @RequestBody AiIdeasRequest r) { return svc.ideas(r); }
    @PostMapping("/hooks") @ResponseStatus(HttpStatus.CREATED) public ContentAiResultDto hooks(@Valid @RequestBody AiHooksRequest r) { return svc.hooks(r); }
    @PostMapping("/copies") @ResponseStatus(HttpStatus.CREATED) public ContentAiResultDto copies(@Valid @RequestBody AiCopiesRequest r) { return svc.copies(r); }
}
