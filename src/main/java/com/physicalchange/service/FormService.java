package com.physicalchange.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Formularios de la Librería y sus preguntas (docs/api-libreria-v2.md). Guardar sustituye todas las preguntas. */
@Service
public class FormService {
    private static final Set<String> WITH_OPTIONS = Set.of("SINGLE", "MULTIPLE");

    private final LibraryFormRepository forms;
    private final LibraryFormQuestionRepository questions;
    private final UserRepository users;
    private final AccessService access;
    private final ObjectMapper json;

    public FormService(LibraryFormRepository f, LibraryFormQuestionRepository q, UserRepository u, AccessService a, ObjectMapper j) {
        forms = f; questions = q; users = u; access = a; json = j;
    }

    @Transactional(readOnly = true)
    public List<FormDto> list() {
        Long me = access.current().id();
        List<LibraryForm> list = forms.findByTrainerIdOrderByUpdatedAtDesc(me);
        if (list.isEmpty()) return List.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : questions.countByForms(list.stream().map(LibraryForm::getId).toList())) counts.put((Long) row[0], (Long) row[1]);
        String creator = creatorName(me);
        return list.stream().map(f -> toDto(f, counts.getOrDefault(f.getId(), 0L), creator)).toList();
    }

    @Transactional(readOnly = true)
    public FormDetailDto get(Long id) { return detail(owned(id)); }

    @Transactional
    public FormDetailDto create(FormRequest r) {
        LibraryForm f = new LibraryForm();
        f.setTrainerId(access.current().id());
        return detail(apply(f, r));
    }

    @Transactional
    public FormDetailDto update(Long id, FormRequest r) {
        return detail(apply(owned(id), r));
    }

    @Transactional
    public FormDetailDto duplicate(Long id) {
        LibraryForm src = owned(id);
        LibraryForm f = new LibraryForm();
        f.setTrainerId(src.getTrainerId());
        f.setName(src.getName().length() > 150 ? src.getName().substring(0, 150) + " (copia)" : src.getName() + " (copia)");
        f.setDescription(src.getDescription());
        f.setAutoSend(false);
        f = forms.save(f);
        for (LibraryFormQuestion q : questions.findByFormIdOrderByPositionAsc(src.getId())) {
            LibraryFormQuestion c = new LibraryFormQuestion();
            c.setFormId(f.getId()); c.setPosition(q.getPosition()); c.setKind(q.getKind());
            c.setLabel(q.getLabel()); c.setRequired(q.isRequired()); c.setOptions(q.getOptions());
            questions.save(c);
        }
        return detail(f);
    }

    @Transactional
    public void delete(Long id) {
        LibraryForm f = owned(id);
        questions.deleteByFormId(id);
        forms.delete(f);
    }

    // ---------- internos ----------

    private LibraryForm owned(Long id) {
        LibraryForm f = forms.findById(id).orElseThrow(() -> new NotFoundException("Formulario no encontrado"));
        access.assertOwnerOrAdmin(f.getTrainerId());
        return f;
    }

    /** Valida, copia los datos (guardando el formulario si es nuevo) y sustituye las preguntas. */
    private LibraryForm apply(LibraryForm f, FormRequest r) {
        for (QuestionRequest q : r.questions()) {
            int n = q.options() == null ? 0 : q.options().size();
            if (WITH_OPTIONS.contains(q.kind()) && n < 2) throw new BadRequestException("La pregunta \"" + q.label() + "\" necesita al menos 2 opciones");
        }
        f.setName(r.name().trim());
        f.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        f.setAutoSend(r.autoSend());
        f.touch();
        if (f.getId() == null) f = forms.save(f);
        questions.deleteByFormId(f.getId());
        questions.flush();
        int pos = 1;
        for (QuestionRequest q : r.questions()) {
            LibraryFormQuestion e = new LibraryFormQuestion();
            e.setFormId(f.getId());
            e.setPosition(pos++);
            e.setKind(q.kind());
            e.setLabel(q.label().trim());
            e.setRequired(q.required());
            List<String> opts = WITH_OPTIONS.contains(q.kind()) ? q.options().stream().map(String::trim).toList() : List.of();
            try { e.setOptions(json.writeValueAsString(opts)); }
            catch (JsonProcessingException ex) { throw new BadRequestException("Opciones no válidas"); }
            questions.save(e);
        }
        return f;
    }

    private FormDetailDto detail(LibraryForm f) {
        List<QuestionDto> list = questions.findByFormIdOrderByPositionAsc(f.getId()).stream()
                .map(q -> new QuestionDto(q.getId(), q.getKind(), q.getLabel(), q.isRequired(), readOptions(q.getOptions()))).toList();
        return new FormDetailDto(toDto(f, list.size(), creatorName(f.getTrainerId())), list);
    }

    private List<String> readOptions(String raw) {
        try { return json.readValue(raw, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }

    private static FormDto toDto(LibraryForm f, long count, String creator) {
        return new FormDto(f.getId(), f.getName(), f.getDescription(), f.isAutoSend(), count, creator, f.getCreatedAt(), f.getUpdatedAt());
    }

    private String creatorName(Long id) { return users.findById(id).map(AppUser::getFullName).orElse("-"); }
}
