package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Librería V2: programas, documentos, formularios, plantillas y logros (docs/api-libreria-v2.md). */
@RestController @RequestMapping("/api/library") @Tag(name = "Librería V2")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class LibraryController {
    private final ProgramService programs;
    private final DocumentService documents;
    private final FormService forms;
    private final TemplateService templates;
    private final AchievementService achievements;

    public LibraryController(ProgramService p, DocumentService d, FormService f, TemplateService t, AchievementService a) {
        programs = p; documents = d; forms = f; templates = t; achievements = a;
    }

    // ---- Programas ----
    @GetMapping("/programs") public List<ProgramDto> programs() { return programs.list(); }
    @GetMapping("/programs/{id}") public ProgramDetailDto program(@PathVariable Long id) { return programs.get(id); }
    @PostMapping("/programs") @ResponseStatus(HttpStatus.CREATED) public ProgramDetailDto createProgram(@Valid @RequestBody ProgramRequest r) { return programs.create(r); }
    @PutMapping("/programs/{id}") public ProgramDetailDto updateProgram(@PathVariable Long id, @Valid @RequestBody ProgramRequest r) { return programs.update(id, r); }
    @PostMapping("/programs/{id}/duplicate") @ResponseStatus(HttpStatus.CREATED) public ProgramDetailDto duplicateProgram(@PathVariable Long id) { return programs.duplicate(id); }
    @DeleteMapping("/programs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteProgram(@PathVariable Long id) { programs.delete(id); }

    // ---- Documentos ----
    @GetMapping("/documents") public List<DocumentDto> documents() { return documents.list(); }
    @PostMapping("/documents") @ResponseStatus(HttpStatus.CREATED) public DocumentDto createDocument(@Valid @RequestBody DocumentRequest r) { return documents.create(r); }
    @PutMapping("/documents/{id}") public DocumentDto updateDocument(@PathVariable Long id, @Valid @RequestBody DocumentRequest r) { return documents.update(id, r); }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteDocument(@PathVariable Long id) { documents.delete(id); }

    // ---- Formularios ----
    @GetMapping("/forms") public List<FormDto> forms() { return forms.list(); }
    @GetMapping("/forms/{id}") public FormDetailDto form(@PathVariable Long id) { return forms.get(id); }
    @PostMapping("/forms") @ResponseStatus(HttpStatus.CREATED) public FormDetailDto createForm(@Valid @RequestBody FormRequest r) { return forms.create(r); }
    @PutMapping("/forms/{id}") public FormDetailDto updateForm(@PathVariable Long id, @Valid @RequestBody FormRequest r) { return forms.update(id, r); }
    @PostMapping("/forms/{id}/duplicate") @ResponseStatus(HttpStatus.CREATED) public FormDetailDto duplicateForm(@PathVariable Long id) { return forms.duplicate(id); }
    @DeleteMapping("/forms/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteForm(@PathVariable Long id) { forms.delete(id); }

    // ---- Plantillas ----
    @GetMapping("/templates") public List<PackDto> packs() { return templates.list(); }
    @GetMapping("/templates/{packId}") public PackDto pack(@PathVariable Long packId) { return templates.get(packId); }
    @PostMapping("/templates/items/{itemId}/copy") @ResponseStatus(HttpStatus.CREATED) public CopyResultDto copy(@PathVariable Long itemId) { return templates.copy(itemId); }

    // ---- Logros ----
    @GetMapping("/achievements") public List<CategoryDto> achievementCategories() { return achievements.list(); }
    @PostMapping("/achievements/categories") @ResponseStatus(HttpStatus.CREATED) public CategoryDto createCategory(@Valid @RequestBody CategoryRequest r) { return achievements.createCategory(r); }
    @PutMapping("/achievements/categories/{id}") public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest r) { return achievements.updateCategory(id, r); }
    @DeleteMapping("/achievements/categories/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteCategory(@PathVariable Long id) { achievements.deleteCategory(id); }
    @PostMapping("/achievements") @ResponseStatus(HttpStatus.CREATED) public AchievementDto createAchievement(@Valid @RequestBody AchievementRequest r) { return achievements.create(r); }
    @PutMapping("/achievements/{id}") public AchievementDto updateAchievement(@PathVariable Long id, @Valid @RequestBody AchievementRequest r) { return achievements.update(id, r); }
    @DeleteMapping("/achievements/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteAchievement(@PathVariable Long id) { achievements.delete(id); }
    @PatchMapping("/achievements/{id}/hidden") public AchievementDto setHidden(@PathVariable Long id, @RequestBody HiddenRequest r) { return achievements.setHidden(id, r); }
}
