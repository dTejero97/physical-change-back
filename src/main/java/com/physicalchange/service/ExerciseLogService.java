package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.mapper.ExerciseLogMapper;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.TrainingMath;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Series que levanta el cliente, y progreso de fuerza y musculatura derivado de ellas. */
@Service
public class ExerciseLogService {
    private static final String OTHERS = "Otros";

    private final ExerciseLogRepository logs;
    private final ExerciseRepository exercises;
    private final WorkoutAssignmentRepository assignments;
    private final WorkoutItemRepository items;
    private final AccessService access;
    private final ApplicationEventPublisher publisher;

    public ExerciseLogService(ExerciseLogRepository l, ExerciseRepository e, WorkoutAssignmentRepository a,
                              WorkoutItemRepository i, AccessService ac, ApplicationEventPublisher p) {
        logs = l; exercises = e; assignments = a; items = i; access = ac; publisher = p;
    }

    /** El cliente registra sus propias series; el entrenador (o admin) las registra para un cliente suyo. */
    @Transactional
    public ExerciseLogDto log(ExerciseLogRequest r) {
        AuthUser me = access.current();
        Long clientId = me.role() == Role.CLIENT ? me.id() : r.clientId();
        if (clientId == null) throw new ConflictException("Indica el cliente");
        access.assertCanAccessClient(clientId);

        Exercise e = exercises.findById(r.exerciseId()).orElseThrow(() -> new NotFoundException("Ejercicio no encontrado"));
        Long workoutId = null;
        if (r.assignmentId() != null) {
            WorkoutAssignment a = assignments.findById(r.assignmentId()).orElseThrow(() -> new NotFoundException("Asignación no encontrada"));
            if (!a.getClientId().equals(clientId)) throw new ForbiddenException("La asignación no es de este cliente");
            workoutId = a.getWorkoutId();
        }
        if (r.workoutItemId() != null) {
            WorkoutItem item = items.findById(r.workoutItemId()).orElseThrow(() -> new NotFoundException("Ejercicio de la rutina no encontrado"));
            if (workoutId != null && !item.getWorkoutId().equals(workoutId)) throw new ConflictException("El ejercicio no pertenece a la asignación");
            if (!item.getExerciseId().equals(e.getId())) throw new ConflictException("El ejercicio no coincide con la rutina");
        }

        ExerciseLog l = new ExerciseLog();
        l.setClientId(clientId);
        l.setAssignmentId(r.assignmentId());
        l.setWorkoutItemId(r.workoutItemId());
        l.setExerciseId(e.getId());
        l.setLogDate(r.logDate());
        l.setSetNumber(r.setNumber());
        l.setReps(r.reps());
        l.setWeightKg(r.weightKg());
        ExerciseLogDto dto = ExerciseLogMapper.toDto(logs.save(l), e);
        publisher.publishEvent(new ClientActivityEvent(clientId));
        return dto;
    }

    @Transactional(readOnly = true)
    public List<ExerciseLogDto> history(Long clientId, Long exerciseId, LocalDate from) {
        access.assertCanAccessClient(clientId);
        return logs.search(clientId, exerciseId, from).stream()
                .map(l -> ExerciseLogMapper.toDto(l, exercises.findById(l.getExerciseId()).orElseThrow()))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        ExerciseLog l = logs.findById(id).orElseThrow(() -> new NotFoundException("Registro no encontrado"));
        access.assertCanAccessClient(l.getClientId());
        logs.delete(l);
        // Recalcula estadísticas; los logros ya conseguidos no se pierden (ADR-007 §3).
        publisher.publishEvent(new ClientActivityEvent(l.getClientId()));
    }

    /**
     * Progreso de fuerza por ejercicio (peso máximo, 1RM estimado y volumen por día)
     * y carga por grupo muscular (series y volumen acumulados). Base del panel de progresos.
     */
    @Transactional(readOnly = true)
    public StrengthReportDto strength(Long clientId, LocalDate from) {
        access.assertCanAccessClient(clientId);
        List<ExerciseLog> all = logs.search(clientId, null, from);

        Map<Long, Exercise> byId = new HashMap<>();
        for (ExerciseLog l : all) byId.computeIfAbsent(l.getExerciseId(), id -> exercises.findById(id).orElseThrow());

        // Progreso por ejercicio, agrupado por día
        Map<Long, Map<LocalDate, List<ExerciseLog>>> perExercise = new LinkedHashMap<>();
        for (ExerciseLog l : all) {
            perExercise.computeIfAbsent(l.getExerciseId(), id -> new TreeMap<>())
                       .computeIfAbsent(l.getLogDate(), d -> new ArrayList<>()).add(l);
        }
        List<ExerciseProgressDto> exerciseProgress = new ArrayList<>();
        for (var entry : perExercise.entrySet()) {
            Exercise e = byId.get(entry.getKey());
            List<StrengthPointDto> points = new ArrayList<>();
            for (var day : entry.getValue().entrySet()) {
                BigDecimal maxW = day.getValue().stream().map(ExerciseLog::getWeightKg).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
                BigDecimal best1Rm = day.getValue().stream()
                        .map(l -> TrainingMath.estimated1Rm(l.getWeightKg(), l.getReps()))
                        .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
                BigDecimal vol = day.getValue().stream()
                        .map(l -> TrainingMath.volume(l.getWeightKg(), l.getReps()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                points.add(new StrengthPointDto(day.getKey(), maxW, best1Rm, vol));
            }
            exerciseProgress.add(new ExerciseProgressDto(e.getId(), e.getName(), muscle(e), points));
        }

        // Carga por grupo muscular
        Map<String, int[]> setsByMuscle = new TreeMap<>();
        Map<String, BigDecimal> volumeByMuscle = new TreeMap<>();
        for (ExerciseLog l : all) {
            String m = muscle(byId.get(l.getExerciseId()));
            setsByMuscle.computeIfAbsent(m, k -> new int[1])[0]++;
            volumeByMuscle.merge(m, TrainingMath.volume(l.getWeightKg(), l.getReps()), BigDecimal::add);
        }
        List<MuscleVolumeDto> muscles = setsByMuscle.entrySet().stream()
                .map(e -> new MuscleVolumeDto(e.getKey(), e.getValue()[0], volumeByMuscle.get(e.getKey())))
                .sorted(Comparator.comparing(MuscleVolumeDto::volumeKg).reversed())
                .toList();

        return new StrengthReportDto(exerciseProgress, muscles);
    }

    private static String muscle(Exercise e) {
        return e.getMuscleGroup() == null || e.getMuscleGroup().isBlank() ? OTHERS : e.getMuscleGroup();
    }
}
