package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.ActivityDto;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.DateTimes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Registro de acciones de la agenda (crear sesión, reservar, cancelar...). Ver docs/api-agenda.md. */
@Service
public class AgendaActivityService {
    private final AgendaActivityRepository activity;
    private final UserRepository users;
    private final AccessService access;

    public AgendaActivityService(AgendaActivityRepository a, UserRepository u, AccessService ac) {
        activity = a; users = u; access = ac;
    }

    /** Anota una acción del usuario actual sobre un evento del profesional dueño. */
    @Transactional
    public void log(AgendaEvent e, String action) {
        AuthUser me = access.current();
        AgendaActivity a = new AgendaActivity();
        a.setTrainerId(e.getTrainerId());
        a.setActorId(me.id());
        a.setActorName(users.findById(me.id()).map(AppUser::getFullName).orElse("-"));
        a.setActorKind(me.role() == Role.CLIENT ? "CLIENT" : "TRAINER");
        a.setAction(action);
        a.setEventTitle(e.getTitle());
        a.setEventEmoji(e.getEmoji());
        a.setEventStart(e.getStartAt());
        a.setRecurrent(e.getSeriesKey() != null);
        activity.save(a);
    }

    /** profile: ALL | CLIENTS | TEAM. actorIds vacío = todos. */
    @Transactional(readOnly = true)
    public List<ActivityDto> list(LocalDate from, LocalDate to, String profile, Set<Long> actorIds) {
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) throw new BadRequestException("Rango de fechas no válido");
        String kind = switch (profile == null ? "ALL" : profile) {
            case "ALL" -> null;
            case "CLIENTS" -> "CLIENT";
            case "TEAM" -> "TEAM";
            default -> throw new BadRequestException("profile debe ser ALL, CLIENTS o TEAM");
        };
        return activity.findByTrainerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        access.current().id(), DateTimes.startOfDay(from), DateTimes.endExclusive(to)).stream()
                .filter(a -> kind == null || kind.equals(a.getActorKind()))
                .filter(a -> actorIds.isEmpty() || actorIds.contains(a.getActorId()))
                .map(a -> new ActivityDto(a.getId(), a.getCreatedAt(), a.getActorId(), a.getActorName(), a.getActorKind(),
                        a.getAction(), a.getEventTitle(), a.getEventEmoji(), a.getEventStart(), a.isRecurrent()))
                .toList();
    }
}
