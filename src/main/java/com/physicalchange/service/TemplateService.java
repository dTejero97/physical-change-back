package com.physicalchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.AccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Catálogo de plantillas del sistema (solo lectura) y copia a la librería del profesional (docs/api-libreria-v2.md).
 * Copiar reutiliza ejercicios globales o propios con el mismo nombre y crea los que falten como propios.
 */
@Service
public class TemplateService {
    private final TemplatePackRepository packs;
    private final TemplateItemRepository items;
    private final WorkoutRepository workouts;
    private final WorkoutItemRepository workoutItems;
    private final ExerciseRepository exercises;
    private final ProgramService programs;
    private final AccessService access;
    private final ObjectMapper json;

    public TemplateService(TemplatePackRepository p, TemplateItemRepository i, WorkoutRepository w, WorkoutItemRepository wi,
                           ExerciseRepository e, ProgramService ps, AccessService a, ObjectMapper j) {
        packs = p; items = i; workouts = w; workoutItems = wi; exercises = e; programs = ps; access = a; json = j;
    }

    @Transactional(readOnly = true)
    public List<PackDto> list() {
        Map<Long, List<TemplateItemDto>> byPack = items.findAllByOrderByPackIdAscPositionAsc().stream()
                .collect(Collectors.groupingBy(TemplateItem::getPackId, LinkedHashMap::new, Collectors.mapping(TemplateService::toDto, Collectors.toList())));
        return packs.findAllByOrderByPositionAsc().stream()
                .map(p -> new PackDto(p.getId(), p.getKind(), p.getName(), p.getDescription(), p.getCover(), byPack.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PackDto get(Long packId) {
        TemplatePack p = packs.findById(packId).orElseThrow(() -> new NotFoundException("Pack no encontrado"));
        return new PackDto(p.getId(), p.getKind(), p.getName(), p.getDescription(), p.getCover(),
                items.findByPackIdOrderByPositionAsc(p.getId()).stream().map(TemplateService::toDto).toList());
    }

    @Transactional
    public CopyResultDto copy(Long itemId) {
        TemplateItem t = items.findById(itemId).orElseThrow(() -> new NotFoundException("Plantilla no encontrada"));
        TemplatePack p = packs.findById(t.getPackId()).orElseThrow();
        Long me = access.current().id();
        JsonNode payload;
        try { payload = json.readTree(t.getPayload()); }
        catch (Exception e) { throw new IllegalStateException("Plantilla con formato no válido: " + t.getId(), e); }

        if ("WORKOUT".equals(p.getKind())) {
            Workout w = createWorkout(me, t.getName(), t.getDescription(), payload.path("exercises"));
            return new CopyResultDto("WORKOUT", w.getId(), w.getName());
        }
        // PROGRAM: crea sus workouts y un programa que los coloca en las semanas.
        List<Workout> created = new ArrayList<>();
        for (JsonNode wn : payload.path("workouts")) created.add(createWorkout(me, wn.path("name").asText(), null, wn.path("exercises")));
        List<LibraryProgramItem> content = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        for (JsonNode s : payload.path("schedule")) {
            Workout w = created.get(s.path("workout").asInt());
            LibraryProgramItem it = new LibraryProgramItem();
            it.setWeek(s.path("week").asInt());
            it.setDay(s.path("day").asInt());
            it.setKind("WORKOUT");
            it.setRefId(w.getId());
            it.setTitle(w.getName());
            it.setPosition(positions.merge(it.getWeek() + "-" + it.getDay(), 1, Integer::sum));
            content.add(it);
        }
        LibraryProgram prog = programs.createRaw(me, t.getName(), t.getDescription(), payload.path("weeks").asInt(1), content);
        return new CopyResultDto("PROGRAM", prog.getId(), prog.getName());
    }

    // ---------- internos ----------

    private Workout createWorkout(Long trainerId, String name, String notes, JsonNode exerciseList) {
        Workout w = new Workout();
        w.setTrainerId(trainerId);
        w.setName(name.length() > 120 ? name.substring(0, 120) : name);
        w.setNotes(notes);
        w = workouts.save(w);
        int pos = 1;
        for (JsonNode e : exerciseList) {
            WorkoutItem it = new WorkoutItem();
            it.setWorkoutId(w.getId());
            it.setExerciseId(exercise(trainerId, e.path("name").asText(), e.path("muscle").asText(null)).getId());
            it.setPosition(pos++);
            it.setSets(e.path("sets").asInt(3));
            it.setReps(e.path("reps").asInt(10));
            it.setWeightKg(BigDecimal.ZERO);
            it.setRestSeconds(e.has("rest") ? e.path("rest").asInt() : null);
            workoutItems.save(it);
        }
        return w;
    }

    private Exercise exercise(Long trainerId, String name, String muscle) {
        return exercises.findVisibleByName(trainerId, name).stream().findFirst().orElseGet(() -> {
            Exercise e = new Exercise();
            e.setName(name);
            e.setMuscleGroup(muscle);
            e.setOwnerId(trainerId);
            return exercises.save(e);
        });
    }

    private static TemplateItemDto toDto(TemplateItem i) { return new TemplateItemDto(i.getId(), i.getName(), i.getDescription(), i.getDetail()); }
}
