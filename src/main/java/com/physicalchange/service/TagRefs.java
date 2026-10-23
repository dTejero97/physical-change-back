package com.physicalchange.service;

import com.physicalchange.domain.ClientTag;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.repository.ClientTagRepository;
import org.springframework.stereotype.Component;
import java.util.*;

/** Comprueba que las etiquetas indicadas en un formulario son del profesional (visibilidad por etiquetas). */
@Component
public class TagRefs {
    private final ClientTagRepository tags;
    public TagRefs(ClientTagRepository t) { tags = t; }

    /** Devuelve el conjunto validado; si alguna no existe o es ajena, 400. required: al menos una. */
    public Set<Long> own(List<Long> ids, Long trainerId, boolean required) {
        Set<Long> set = ids == null ? new HashSet<>() : new HashSet<>(ids);
        set.remove(null);
        if (required && set.isEmpty()) throw new BadRequestException("Elige al menos una etiqueta");
        if (set.isEmpty()) return set;
        List<ClientTag> found = tags.findAllById(set);
        if (found.size() != set.size() || found.stream().anyMatch(t -> !t.getTrainerId().equals(trainerId))) {
            throw new BadRequestException("Etiqueta no válida");
        }
        return set;
    }
}
