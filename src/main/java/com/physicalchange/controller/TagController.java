package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.TagService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/tags") @Tag(name = "Etiquetas de clientes")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class TagController {
    private final TagService svc;
    public TagController(TagService s) { svc = s; }

    @GetMapping public List<TagDto> list() { return svc.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public TagDto create(@Valid @RequestBody TagRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public TagDto update(@PathVariable Long id, @Valid @RequestBody TagRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }
    @PostMapping("/{id}/clients") public TagDto assign(@PathVariable Long id, @Valid @RequestBody TagClientsRequest r) { return svc.assign(id, r); }
    @DeleteMapping("/{id}/clients/{clientId}") public TagDto unassign(@PathVariable Long id, @PathVariable Long clientId) { return svc.unassign(id, clientId); }
}
