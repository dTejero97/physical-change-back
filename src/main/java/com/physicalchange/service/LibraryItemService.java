package com.physicalchange.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Librería guardada como documentos JSON por profesional (docs/api-libreria.md, T27):
 * cardio, vídeos, recetas, planes de nutrición, agrupaciones y alimentos, más las preferencias por clave.
 * El servidor no interpreta el contenido: exige objeto JSON, tamaño máximo y que no lleve imágenes en data: URL.
 */
@Service
public class LibraryItemService {
    static final int ITEM_MAX_BYTES = 200 * 1024;
    static final int PREF_MAX_BYTES = 512 * 1024;
    static final int MAX_PER_KIND = 2000;
    static final Set<String> PREF_KEYS = Set.of("EXERCISE_META", "EXERCISE_TAGS", "WORKOUT_META", "FOOD_META");

    private final LibraryItemRepository items;
    private final LibraryPrefRepository prefs;
    private final FileService files;
    private final UserRepository users;
    private final AccessService access;
    private final ObjectMapper json;

    public LibraryItemService(LibraryItemRepository i, LibraryPrefRepository p, FileService f, UserRepository u, AccessService a, ObjectMapper j) {
        items = i; prefs = p; files = f; users = u; access = a; json = j;
    }

    @Transactional(readOnly = true)
    public List<LibraryItemDto> list(String kind) {
        Long me = access.current().id();
        String creator = creator(me);
        return items.findByTrainerIdAndKindOrderByCreatedAtDesc(me, kind).stream().map(i -> toDto(i, creator)).toList();
    }

    @Transactional(readOnly = true)
    public LibraryItemDto get(Long id) { LibraryItem i = owned(id); return toDto(i, creator(i.getTrainerId())); }

    @Transactional
    public LibraryItemDto create(LibraryItemRequest r) {
        Long me = access.current().id();
        if (items.countByTrainerIdAndKind(me, r.kind()) >= MAX_PER_KIND) throw new ConflictException("Has llegado al máximo de elementos de este tipo");
        LibraryItem i = new LibraryItem();
        i.setTrainerId(me);
        i.setKind(r.kind());
        apply(i, r, null);
        return toDto(items.save(i), creator(me));
    }

    /** Los archivos que dejan de estar enlazados se borran. */
    @Transactional
    public LibraryItemDto update(Long id, LibraryItemRequest r) {
        LibraryItem i = owned(id);
        if (!i.getKind().equals(r.kind())) throw new BadRequestException("No se puede cambiar el tipo de un elemento");
        List<Long> old = new ArrayList<>(i.getFileIds());
        apply(i, r, i.getId());
        i.touch();
        items.flush();
        old.stream().filter(f -> !i.getFileIds().contains(f)).forEach(files::removeById);
        return toDto(i, creator(i.getTrainerId()));
    }

    @Transactional
    public void delete(Long id) {
        LibraryItem i = owned(id);
        List<Long> old = new ArrayList<>(i.getFileIds());
        items.delete(i);
        items.flush();
        old.forEach(files::removeById);
    }

    @Transactional(readOnly = true)
    public LibraryPrefDto pref(String key) {
        checkKey(key);
        return prefs.findById(new LibraryPref.Key(access.current().id(), key))
                .map(p -> { JsonNode d = read(p.getData()); return new LibraryPrefDto(key, d, p.getUpdatedAt(), fileRefs(d)); })
                .orElseGet(() -> new LibraryPrefDto(key, json.createObjectNode(), null, List.of()));
    }

    @Transactional
    public LibraryPrefDto savePref(String key, JsonNode data) {
        checkKey(key);
        String text = validate(data, PREF_MAX_BYTES, "Las preferencias superan el tamaño máximo (512 KB)");
        Long me = access.current().id();
        LibraryPref p = prefs.findById(new LibraryPref.Key(me, key)).orElseGet(() -> new LibraryPref(me, key));
        p.setData(text);
        prefs.save(p);
        return new LibraryPrefDto(key, data, p.getUpdatedAt(), fileRefs(data));
    }

    /** Imágenes citadas como "file:<id>" en una preferencia (p. ej. la imagen de un ejercicio): URL firmada de las propias. */
    private List<FileDto> fileRefs(JsonNode data) {
        Set<Long> ids = new LinkedHashSet<>();
        collectRefs(data, ids);
        Long me = access.current().id();
        return files.ownedAmong(ids, me).stream().map(f -> new FileDto(f, null, null, 0, files.url(f))).toList();
    }

    private static void collectRefs(JsonNode n, Set<Long> out) {
        if (n.isTextual() && n.asText().matches("file:\\d{1,18}")) out.add(Long.parseLong(n.asText().substring(5)));
        for (JsonNode c : n) collectRefs(c, out);
    }

    // ---------- internos ----------

    private void apply(LibraryItem i, LibraryItemRequest r, Long itemId) {
        i.setName(r.name().trim());
        i.setData(validate(r.data(), ITEM_MAX_BYTES, "El contenido supera el tamaño máximo (200 KB)"));
        List<Long> ids = r.fileIds() == null ? List.of() : r.fileIds().stream().distinct().toList();
        for (Long f : ids) {
            files.owned(f, "IMAGE");
            if (items.fileUsedElsewhere(f, itemId == null ? -1L : itemId)) throw new ConflictException("Esa imagen ya pertenece a otro elemento");
        }
        i.getFileIds().clear();
        i.getFileIds().addAll(ids);
    }

    /** Objeto JSON, tamaño máximo y sin imágenes incrustadas. Devuelve el texto que se guarda. */
    private String validate(JsonNode data, int max, String tooBig) {
        if (data == null || !data.isObject()) throw new BadRequestException("El contenido debe ser un objeto JSON");
        String text;
        try { text = json.writeValueAsString(data); } catch (JsonProcessingException e) { throw new BadRequestException("Contenido no válido"); }
        if (text.getBytes(StandardCharsets.UTF_8).length > max) throw new BadRequestException(tooBig);
        if (hasDataUrl(data)) throw new BadRequestException("Las imágenes se suben como archivo, no dentro del contenido");
        return text;
    }

    private static boolean hasDataUrl(JsonNode n) {
        if (n.isTextual()) return n.asText().regionMatches(true, 0, "data:", 0, 5);
        for (JsonNode c : n) if (hasDataUrl(c)) return true;
        return false;
    }

    private JsonNode read(String text) {
        try { return json.readTree(text); } catch (JsonProcessingException e) { return json.createObjectNode(); }
    }

    private void checkKey(String key) { if (!PREF_KEYS.contains(key)) throw new BadRequestException("Clave de preferencia no válida"); }

    /** Propio; lo ajeno responde 404 (no se revela que existe). */
    private LibraryItem owned(Long id) {
        LibraryItem i = items.findById(id).orElseThrow(() -> new NotFoundException("Elemento no encontrado"));
        if (!i.getTrainerId().equals(access.current().id())) throw new NotFoundException("Elemento no encontrado");
        return i;
    }

    private String creator(Long id) { return users.findById(id).map(AppUser::getFullName).orElse("-"); }

    private LibraryItemDto toDto(LibraryItem i, String creator) {
        List<FileDto> fs = i.getFileIds().stream().map(f -> new FileDto(f, null, null, 0, files.url(f))).toList();
        return new LibraryItemDto(i.getId(), i.getKind(), i.getName(), read(i.getData()), fs, creator, i.getCreatedAt(), i.getUpdatedAt());
    }
}
