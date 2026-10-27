package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ai.AiChatService;
import com.physicalchange.service.ai.AiService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Physical AI (docs/api-ia.md). Solo TRAINER y ADMIN; todo se refiere al profesional autenticado. */
@RestController @RequestMapping("/api/ai") @Tag(name = "Physical AI")
@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
public class AiController {
    private final AiService ai;
    private final AiChatService chat;
    public AiController(AiService a, AiChatService c) { ai = a; chat = c; }

    @GetMapping("/status") public AiStatusDto status() { return ai.status(); }
    @GetMapping("/instructions") public AiInstructionsDto instructions() { return ai.instructions(); }
    @PutMapping("/instructions") public AiInstructionsDto saveInstructions(@Valid @RequestBody AiInstructionsRequest r) { return ai.saveInstructions(r.text()); }

    @PostMapping("/generate/workout") @ResponseStatus(HttpStatus.CREATED)
    public AiDraftDto workout(@Valid @RequestBody AiWorkoutRequest r) { return ai.generateWorkout(r); }
    @PostMapping("/generate/program") @ResponseStatus(HttpStatus.CREATED)
    public AiDraftDto program(@Valid @RequestBody AiProgramRequest r) { return ai.generateProgram(r); }
    /** Plan diario y receta: 409 hasta que la nutrición de la Librería esté en el servidor (T27). */
    @PostMapping({"/generate/daily-plan", "/generate/recipe"}) @ResponseStatus(HttpStatus.CREATED)
    public AiDraftDto nutrition(@RequestBody(required = false) Object ignored) { return ai.nutritionPending(); }

    @GetMapping("/suggestions") public List<AiSuggestionDto> suggestions(@RequestParam(required = false) String context) { return chat.suggestions(); }
    @GetMapping("/conversations") public List<AiConversationDto> conversations() { return chat.list(); }
    @PostMapping("/conversations") @ResponseStatus(HttpStatus.CREATED)
    public AiConversationDto create(@Valid @RequestBody(required = false) AiConversationRequest r) { return chat.create(r == null ? null : r.context()); }
    @GetMapping("/conversations/{id}") public AiConversationDetailDto get(@PathVariable Long id) { return chat.get(id); }
    @PostMapping("/conversations/{id}/messages") @ResponseStatus(HttpStatus.CREATED)
    public AiSendMessageResponse send(@PathVariable Long id, @Valid @RequestBody AiSendMessageRequest r) { return chat.send(id, r); }
    @DeleteMapping("/conversations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { chat.delete(id); }
}
