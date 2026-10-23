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
import java.util.stream.Collectors;

/** Horarios de citas: franjas semanales en las que los clientes reservan citas individuales (docs/api-agenda.md). */
@Service
public class ScheduleService {
    private final AppointmentScheduleRepository schedules;
    private final SessionTypeService types;
    private final FileService files;
    private final TagRefs tags;
    private final UserRepository users;
    private final ClientProfileRepository profiles;
    private final ClientDirectory directory;
    private final AccessService access;
    private final ObjectMapper json;

    public ScheduleService(AppointmentScheduleRepository s, SessionTypeService t, FileService f, TagRefs tr, UserRepository u,
                           ClientProfileRepository p, ClientDirectory d, AccessService a, ObjectMapper j) {
        schedules = s; types = t; files = f; tags = tr; users = u; profiles = p; directory = d; access = a; json = j;
    }

    @Transactional(readOnly = true)
    public List<ScheduleDto> list() {
        List<AppointmentSchedule> list = schedules.findByTrainerIdOrderByCreatedAtDesc(access.current().id());
        Map<Long, SessionType> typeById = types.byIds(list.stream().map(AppointmentSchedule::getSessionTypeId).collect(Collectors.toSet()));
        String name = nameOf(access.current().id());
        return list.stream().map(s -> toDto(s, typeById.get(s.getSessionTypeId()), name)).toList();
    }

    /** Detalle con los clientes que pueden reservar: activos, no eliminados y, si aplica, con alguna de las etiquetas. */
    @Transactional(readOnly = true)
    public ScheduleDetailDto get(Long id) {
        AppointmentSchedule s = owned(id);
        List<AppUser> clients = directory.clientsOf(s.getTrainerId()).stream().filter(AppUser::isActive).toList();
        Map<Long, ClientProfile> byId = profiles.findByClientIdIn(clients.stream().map(AppUser::getId).toList()).stream()
                .collect(Collectors.toMap(ClientProfile::getClientId, p -> p));
        List<MemberDto> available = clients.stream()
                .filter(c -> byId.get(c.getId()) == null || byId.get(c.getId()).getRemovedAt() == null)
                .filter(c -> !"TAGS".equals(s.getVisibility()) || (byId.get(c.getId()) != null
                        && !Collections.disjoint(byId.get(c.getId()).getTagIds(), s.getTagIds())))
                .sorted(Comparator.comparing(AppUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new MemberDto(c.getId(), c.getFullName())).toList();
        return new ScheduleDetailDto(toDto(s, types.byIds(Set.of(s.getSessionTypeId())).get(s.getSessionTypeId()), nameOf(s.getTrainerId())), available);
    }

    @Transactional
    public ScheduleDto create(ScheduleRequest r) {
        AppointmentSchedule s = new AppointmentSchedule();
        s.setTrainerId(access.current().id());
        apply(s, r);
        return toDto(schedules.save(s));
    }

    @Transactional
    public ScheduleDto update(Long id, ScheduleRequest r) {
        AppointmentSchedule s = owned(id);
        apply(s, r);
        return toDto(s);
    }

    @Transactional
    public ScheduleDto setActive(Long id, ActiveRequest r) {
        AppointmentSchedule s = owned(id);
        s.setActive(r.active());
        return toDto(s);
    }

    @Transactional
    public void delete(Long id) { schedules.delete(owned(id)); }

    // ---------- internos ----------

    private AppointmentSchedule owned(Long id) {
        AppointmentSchedule s = schedules.findById(id).orElseThrow(() -> new NotFoundException("Horario no encontrado"));
        access.assertOwnerOrAdmin(s.getTrainerId());
        return s;
    }

    private void apply(AppointmentSchedule s, ScheduleRequest r) {
        Long trainer = s.getTrainerId();
        boolean online = "ONLINE".equals(r.mode());
        if (online && r.source() == null) throw new BadRequestException("Elige la fuente de la cita online");
        if (r.endDate().isBefore(r.startDate()) || r.endDate().isAfter(r.startDate().plusYears(1))) {
            throw new BadRequestException("El horario debe durar como mucho un año y terminar después de empezar");
        }
        for (SlotDto slot : r.slots()) {
            if (!slot.to().isAfter(slot.from())) throw new BadRequestException("Cada franja debe terminar después de empezar");
        }
        s.setTitle(r.title().trim());
        s.setEmoji(blank(r.emoji()));
        s.setMode(r.mode());
        s.setSource(online ? r.source() : null);
        s.setMeetingUrl(online ? blank(r.meetingUrl()) : null);
        s.setLocation(online ? null : blank(r.location()));
        s.setSessionTypeId(types.ownedForRequest(r.sessionTypeId(), trainer).getId());
        s.setDescription(blank(r.description()));
        s.setCover(files.checkCover(r.cover(), true));
        s.setStartDate(r.startDate());
        s.setEndDate(r.endDate());
        s.setDurationMin(r.durationMin());
        List<SlotDto> sorted = r.slots().stream().sorted(Comparator.comparing(SlotDto::weekday).thenComparing(SlotDto::from)).toList();
        try { s.setSlots(json.writeValueAsString(sorted)); }
        catch (JsonProcessingException e) { throw new BadRequestException("Franjas no válidas"); }
        s.setVisibility(r.visibility());
        s.getTagIds().clear();
        if ("TAGS".equals(r.visibility())) s.getTagIds().addAll(tags.own(r.tagIds(), trainer, true));
    }

    private ScheduleDto toDto(AppointmentSchedule s) {
        return toDto(s, types.byIds(Set.of(s.getSessionTypeId())).get(s.getSessionTypeId()), nameOf(s.getTrainerId()));
    }

    private ScheduleDto toDto(AppointmentSchedule s, SessionType t, String organizer) {
        List<SlotDto> slots;
        try { slots = json.readValue(s.getSlots(), new TypeReference<List<SlotDto>>() {}); }
        catch (JsonProcessingException e) { slots = List.of(); }
        return new ScheduleDto(s.getId(), s.getTitle(), s.getEmoji(), s.getMode(), s.getSource(), s.getMeetingUrl(), s.getLocation(),
                s.getSessionTypeId(), t == null ? null : t.getName(), t == null ? null : t.getColor(), s.getDescription(), s.getCover(),
                files.coverUrl(s.getCover()), s.getStartDate(), s.getEndDate(), s.getDurationMin(), slots, s.getVisibility(),
                new ArrayList<>(s.getTagIds()), s.isActive(), s.getTrainerId(), organizer, s.getCreatedAt());
    }

    private String nameOf(Long id) { return users.findById(id).map(AppUser::getFullName).orElse("-"); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
