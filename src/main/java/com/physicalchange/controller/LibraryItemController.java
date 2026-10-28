package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.LibraryItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Librería en el servidor: elementos JSON y preferencias (docs/api-libreria.md, T27). */
@RestController @RequestMapping("/api/library") @Tag(name = "Librería")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class LibraryItemController {
    private final LibraryItemService svc;
    public LibraryItemController(LibraryItemService s) { svc = s; }

    @GetMapping("/items") public List<LibraryItemDto> list(@RequestParam String kind) { return svc.list(kind); }
    @GetMapping("/items/{id}") public LibraryItemDto get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping("/items") @ResponseStatus(HttpStatus.CREATED)
    public LibraryItemDto create(@Valid @RequestBody LibraryItemRequest r) { return svc.create(r); }
    @PutMapping("/items/{id}") public LibraryItemDto update(@PathVariable Long id, @Valid @RequestBody LibraryItemRequest r) { return svc.update(id, r); }
    @DeleteMapping("/items/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }

    @GetMapping("/prefs/{key}") public LibraryPrefDto pref(@PathVariable String key) { return svc.pref(key); }
    @PutMapping("/prefs/{key}") public LibraryPrefDto savePref(@PathVariable String key, @Valid @RequestBody LibraryPrefRequest r) {
        return svc.savePref(key, r.data());
    }
}
