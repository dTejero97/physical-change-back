package com.physicalchange.service;

import com.physicalchange.domain.SessionType;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Tipos de sesión de cada profesional (docs/api-agenda.md). Un tipo en uso no se borra: se sustituye. */
@Service
public class SessionTypeService {
    /** Tipos que recibe un profesional que aún no tiene ninguno. */
    static final List<String[]> DEFAULTS = List.of(
            new String[]{"Nutrición", "#F07A12"}, new String[]{"Fisioterapia", "#F49AC8"}, new String[]{"CrossTraining", "#C2187A"},
            new String[]{"Yoga", "#8B3FD1"}, new String[]{"Entrenamiento personal", "#5B6CF0"});

    private final SessionTypeRepository types;
    private final AgendaEventRepository events;
    private final AppointmentScheduleRepository schedules;
    private final AccessService access;

    public SessionTypeService(SessionTypeRepository t, AgendaEventRepository e, AppointmentScheduleRepository s, AccessService a) {
        types = t; events = e; schedules = s; access = a;
    }

    @Transactional
    public List<SessionTypeDto> list() {
        Long me = access.current().id();
        if (types.countByTrainerId(me) == 0) {
            for (String[] d : DEFAULTS) {
                SessionType t = new SessionType();
                t.setTrainerId(me); t.setName(d[0]); t.setColor(d[1]);
                types.save(t);
            }
        }
        return types.findByTrainerIdOrderByNameAsc(me).stream().map(this::toDto).toList();
    }

    @Transactional
    public SessionTypeDto create(SessionTypeRequest r) {
        Long me = access.current().id();
        String name = r.name().trim();
        if (types.findByTrainerIdAndNameIgnoreCase(me, name).isPresent()) throw new ConflictException("Ya tienes un tipo de sesión con ese nombre");
        SessionType t = new SessionType();
        t.setTrainerId(me);
        t.setName(name);
        t.setColor(r.color().toUpperCase(Locale.ROOT));
        return toDto(types.save(t));
    }

    @Transactional
    public SessionTypeDto update(Long id, SessionTypeRequest r) {
        SessionType t = owned(id);
        String name = r.name().trim();
        types.findByTrainerIdAndNameIgnoreCase(t.getTrainerId(), name).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ConflictException("Ya tienes un tipo de sesión con ese nombre"); });
        t.setName(name);
        t.setColor(r.color().toUpperCase(Locale.ROOT));
        return toDto(t);
    }

    @Transactional
    public void delete(Long id) {
        SessionType t = owned(id);
        if (usage(id) > 0) throw new ConflictException("El tipo de sesión está en uso: sustitúyelo antes de borrarlo");
        types.delete(t);
    }

    /** Eventos desde hoy y todos los horarios pasan al tipo destino. */
    @Transactional
    public ReplaceResultDto replace(Long id, ReplaceSessionTypeRequest r) {
        owned(id);
        if (id.equals(r.targetId())) throw new BadRequestException("Elige un tipo de sesión distinto");
        owned(r.targetId());
        int moved = events.replaceSessionType(id, r.targetId(), LocalDate.now().atStartOfDay())
                + schedules.replaceSessionType(id, r.targetId());
        return new ReplaceResultDto(moved);
    }

    // ---------- usados por eventos y horarios ----------

    SessionType owned(Long id) {
        SessionType t = types.findById(id).orElseThrow(() -> new NotFoundException("Tipo de sesión no encontrado"));
        access.assertOwnerOrAdmin(t.getTrainerId());
        return t;
    }

    /** Tipo propio indicado en un formulario: si no existe o es ajeno, es un dato no válido. */
    SessionType ownedForRequest(Long id, Long trainerId) {
        return types.findById(id).filter(t -> t.getTrainerId().equals(trainerId))
                .orElseThrow(() -> new BadRequestException("Tipo de sesión no válido"));
    }

    Map<Long, SessionType> byIds(Collection<Long> ids) {
        Map<Long, SessionType> out = new HashMap<>();
        if (!ids.isEmpty()) types.findAllById(ids).forEach(t -> out.put(t.getId(), t));
        return out;
    }

    private long usage(Long id) { return events.countBySessionTypeId(id) + schedules.countBySessionTypeId(id); }

    private SessionTypeDto toDto(SessionType t) {
        return new SessionTypeDto(t.getId(), t.getName(), t.getColor(), t.getCreatedAt(), usage(t.getId()));
    }
}
