package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Programas de la Librería: semanas × 7 días de contenido y automatizaciones (docs/api-libreria-v2.md). */
@Service
public class ProgramService {
    private final LibraryProgramRepository programs;
    private final LibraryProgramItemRepository items;
    private final WorkoutRepository workouts;
    private final UserRepository users;
    private final AccessService access;

    public ProgramService(LibraryProgramRepository p, LibraryProgramItemRepository i, WorkoutRepository w, UserRepository u, AccessService a) {
        programs = p; items = i; workouts = w; users = u; access = a;
    }

    @Transactional(readOnly = true)
    public List<ProgramDto> list() {
        List<LibraryProgram> list = programs.findByTrainerIdOrderByUpdatedAtDesc(access.current().id());
        if (list.isEmpty()) return List.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : items.countByPrograms(list.stream().map(LibraryProgram::getId).toList())) counts.put((Long) row[0], (Long) row[1]);
        Map<Long, String> names = new HashMap<>();
        list.forEach(p -> names.put(p.getId(), p.getName()));
        String creator = creatorName(access.current().id());
        return list.stream().map(p -> toDto(p, counts.getOrDefault(p.getId(), 0L), names.get(p.getLinkedProgramId()), creator)).toList();
    }

    @Transactional(readOnly = true)
    public ProgramDetailDto get(Long id) { return detail(owned(id)); }

    @Transactional
    public ProgramDetailDto create(ProgramRequest r) {
        LibraryProgram p = new LibraryProgram();
        p.setTrainerId(access.current().id());
        return detail(apply(p, r));
    }

    @Transactional
    public ProgramDetailDto update(Long id, ProgramRequest r) {
        return detail(apply(owned(id), r));
    }

    @Transactional
    public ProgramDetailDto duplicate(Long id) {
        LibraryProgram src = owned(id);
        LibraryProgram p = new LibraryProgram();
        p.setTrainerId(src.getTrainerId());
        p.setName(copyName(src.getName()));
        p.setDescription(src.getDescription());
        p.setWeeks(src.getWeeks());
        p.setLinkedProgramId(src.getLinkedProgramId());
        p.setArchiveClient(src.isArchiveClient());
        p = programs.save(p);
        for (LibraryProgramItem i : items.findByProgramIdOrderByWeekAscDayAscPositionAsc(src.getId())) {
            LibraryProgramItem c = new LibraryProgramItem();
            c.setProgramId(p.getId()); c.setWeek(i.getWeek()); c.setDay(i.getDay()); c.setKind(i.getKind());
            c.setRefId(i.getRefId()); c.setTitle(i.getTitle()); c.setPosition(i.getPosition());
            items.save(c);
        }
        return detail(p);
    }

    /** Los programas que lo tenían enlazado quedan sin enlace. */
    @Transactional
    public void delete(Long id) {
        LibraryProgram p = owned(id);
        programs.findByLinkedProgramId(id).forEach(o -> o.setLinkedProgramId(null));
        items.deleteByProgramId(id);
        programs.flush();
        programs.delete(p);
    }

    /** Crea un programa ya validado desde una plantilla (TemplateService). */
    @Transactional
    public LibraryProgram createRaw(Long trainerId, String name, String description, int weeks, List<LibraryProgramItem> content) {
        LibraryProgram p = new LibraryProgram();
        p.setTrainerId(trainerId);
        p.setName(name);
        p.setDescription(description);
        p.setWeeks(weeks);
        p = programs.save(p);
        for (LibraryProgramItem i : content) { i.setProgramId(p.getId()); items.save(i); }
        return p;
    }

    // ---------- internos ----------

    private LibraryProgram owned(Long id) {
        LibraryProgram p = programs.findById(id).orElseThrow(() -> new NotFoundException("Programa no encontrado"));
        access.assertOwnerOrAdmin(p.getTrainerId());
        return p;
    }

    /** Valida, copia los datos (guardando el programa si es nuevo) y sustituye el contenido. */
    private LibraryProgram apply(LibraryProgram p, ProgramRequest r) {
        Long trainer = p.getTrainerId();
        if (r.linkedProgramId() != null) {
            if (r.linkedProgramId().equals(p.getId())) throw new BadRequestException("Un programa no puede enlazarse consigo mismo");
            programs.findById(r.linkedProgramId()).filter(o -> o.getTrainerId().equals(trainer))
                    .orElseThrow(() -> new BadRequestException("Programa enlazado no válido"));
        }
        Set<Long> ownWorkouts = new HashSet<>();
        for (ProgramItemRequest i : r.items()) {
            if (i.week() > r.weeks()) throw new BadRequestException("Hay contenido en una semana que no existe");
            if ("WORKOUT".equals(i.kind()) && !ownWorkouts.contains(i.refId())) {
                if (i.refId() == null || workouts.findById(i.refId()).filter(w -> w.getTrainerId().equals(trainer)).isEmpty()) {
                    throw new BadRequestException("Workout no válido: " + i.title());
                }
                ownWorkouts.add(i.refId());
            }
        }
        p.setName(r.name().trim());
        p.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        p.setWeeks(r.weeks());
        p.setLinkedProgramId(r.linkedProgramId());
        p.setArchiveClient(r.archiveClient());
        p.touch();
        if (p.getId() == null) p = programs.save(p);
        items.deleteByProgramId(p.getId());
        items.flush();
        Map<String, Integer> positions = new HashMap<>();
        for (ProgramItemRequest i : r.items()) {
            LibraryProgramItem it = new LibraryProgramItem();
            it.setProgramId(p.getId());
            it.setWeek(i.week());
            it.setDay(i.day());
            it.setKind(i.kind());
            it.setRefId("NOTE".equals(i.kind()) ? null : i.refId());
            it.setTitle(i.title().trim());
            it.setPosition(positions.merge(i.week() + "-" + i.day(), 1, Integer::sum));
            items.save(it);
        }
        return p;
    }

    private ProgramDetailDto detail(LibraryProgram p) {
        List<ProgramItemDto> list = items.findByProgramIdOrderByWeekAscDayAscPositionAsc(p.getId()).stream()
                .map(i -> new ProgramItemDto(i.getId(), i.getWeek(), i.getDay(), i.getKind(), i.getRefId(), i.getTitle(), i.getPosition()))
                .toList();
        String linked = p.getLinkedProgramId() == null ? null : programs.findById(p.getLinkedProgramId()).map(LibraryProgram::getName).orElse(null);
        return new ProgramDetailDto(toDto(p, list.size(), linked, creatorName(p.getTrainerId())), list);
    }

    private static ProgramDto toDto(LibraryProgram p, long count, String linkedName, String creator) {
        return new ProgramDto(p.getId(), p.getName(), p.getDescription(), p.getWeeks(), p.getLinkedProgramId(), linkedName,
                p.isArchiveClient(), p.getLinkedProgramId() != null || p.isArchiveClient(), count, creator, p.getCreatedAt(), p.getUpdatedAt());
    }

    private String creatorName(Long id) { return users.findById(id).map(AppUser::getFullName).orElse("-"); }

    static String copyName(String name) {
        String n = name + " (copia)";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }
}
