package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.exception.ForbiddenException;
import com.physicalchange.security.AccessService;
import com.physicalchange.security.AuthUser;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utilidades comunes del generador de contenido: espacio de trabajo (ADR-014 §9), zona horaria y conversión a DTOs.
 * Todo pertenece al espacio AccessService.workspaceId(): el ADMIN no ve la marca de los TRAINER ni al revés.
 */
@Component
public class ContentSupport {
    public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    public static final Map<String, String> FORMATS = linked("REEL", "Reel", "CAROUSEL", "Carrusel", "SHORT", "Short", "STORY", "Story",
            "POST", "Post", "LONG_VIDEO", "Vídeo largo");
    public static final Map<String, String> OBJECTIVES = linked("ATTRACT_CLIENTS", "Captar clientes", "AUTHORITY", "Autoridad",
            "COMMUNITY", "Comunidad", "SALES", "Venta");

    private final AccessService access;
    public ContentSupport(AccessService a) { access = a; }

    /**
     * Espacio con el que se trabaja: el propio profesional (ADMIN, TRAINER) o el dueño del equipo para un colaborador con el
     * permiso del generador. CLIENT y colaborador sin permiso: 403.
     */
    public Long workspace() {
        AuthUser me = access.current();
        if (me.role() == Role.CLIENT) throw new ForbiddenException("No tienes acceso al generador de contenido");
        if (me.role() == Role.COLLABORATOR) {
            TeamMember m = access.collaborator();
            if (!m.isCanUseContent()) throw new ForbiddenException("No tienes permiso para usar el generador de contenido");
            return m.getOwnerId();
        }
        return me.id();
    }

    public Long me() { return access.current().id(); }

    // ---------- Conversión ----------

    public static OffsetDateTime odt(Instant i) { return i == null ? null : i.atZone(ZONE).toOffsetDateTime(); }

    public static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Etiquetas sin repetir (sin distinguir mayúsculas), recortadas; null si no hay. */
    public static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        Map<String, String> unique = new LinkedHashMap<>();
        for (String t : tags) { String s = t.strip(); if (!s.isEmpty()) unique.putIfAbsent(s.toLowerCase(Locale.ROOT), s); }
        return unique.isEmpty() ? null : String.join(",", unique.values());
    }

    /** Hashtags sin '#' ni repetidos; null si no hay. */
    public static String joinHashtags(List<String> hashtags) {
        if (hashtags == null || hashtags.isEmpty()) return null;
        Set<String> unique = new LinkedHashSet<>();
        for (String h : hashtags) { String s = h.strip(); if (s.startsWith("#")) s = s.substring(1); if (!s.isEmpty()) unique.add(s); }
        return unique.isEmpty() ? null : String.join(",", unique);
    }

    /** Patrón LIKE en minúsculas para q; null si viene vacío. Se escapan los comodines del usuario. */
    public static String like(String q) {
        if (q == null || q.isBlank()) return null;
        return "%" + q.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
    }

    public static String tagLike(String tag) {
        if (tag == null || tag.isBlank()) return null;
        return "%," + tag.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + ",%";
    }

    public static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    public static String cut(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }

    public static ContentIdeaDto idea(ContentIdea i) {
        return new ContentIdeaDto(i.getId(), i.getTitle(), i.getHook(), i.getScript(), i.getFormat(), i.getObjective(), i.getTheme(),
                split(i.getTags()), i.getOrigin(), i.isBank(), i.getSourceId(), i.isUsed(), i.isFavorite(), odt(i.getCreatedAt()),
                odt(i.getUpdatedAt()));
    }

    /** charLimit/overLimit: null mientras los límites de cada red no se verifiquen contra su documentación oficial (T194). */
    public static ContentSnippetDto snippet(ContentSnippet s) {
        return new ContentSnippetDto(s.getId(), s.getKind(), s.getText(), s.getNetwork(), s.getCta(), split(s.getHashtags()), s.getTheme(),
                s.getObjective(), split(s.getTags()), s.getOrigin(), s.isBank(), s.getSourceId(), s.isFavorite(), odt(s.getCreatedAt()),
                odt(s.getUpdatedAt()), null, null);
    }

    public static List<LabelDto> labels(Map<String, String> m) {
        return m.entrySet().stream().map(e -> new LabelDto(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
