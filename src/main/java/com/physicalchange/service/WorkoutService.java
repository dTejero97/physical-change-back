package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.mapper.WorkoutMapper;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.TrainingMath;
import org.springframework.data.domain.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Rutinas (entrenamientos), sus ejercicios configurados, asignaciones y cumplimiento. */
@Service
public class WorkoutService {
    private final WorkoutRepository workouts;
    private final WorkoutItemRepository items;
    private final WorkoutAssignmentRepository assignments;
    private final ExerciseRepository exercises;
    private final ExerciseLogRepository logs;
    private final AccessService access;
    private final ApplicationEventPublisher publisher;

    public WorkoutService(WorkoutRepository w, WorkoutItemRepository i, WorkoutAssignmentRepository a,
                          ExerciseRepository e, ExerciseLogRepository l, AccessService ac, ApplicationEventPublisher p) {
        workouts = w; items = i; assignments = a; exercises = e; logs = l; access = ac; publisher = p;
    }

    // ---------- Rutinas ----------

    @Transactional(readOnly = true)
    public Page<WorkoutDto> list(Pageable p) {
        return workouts.findByTrainerId(access.workspaceId(), p).map(WorkoutMapper::toDto);
    }

    @Transactional(readOnly = true)
    public WorkoutDetailDto get(Long id) {
        Workout w = ownedWorkout(id);
        return WorkoutMapper.toDetail(w, itemDtos(w));
    }

    @Transactional
    public WorkoutDto create(WorkoutRequest r) {
        Workout w = new Workout();
        w.setTrainerId(access.workspaceId());
        w.setName(r.name());
        w.setNotes(r.notes());
        return WorkoutMapper.toDto(workouts.save(w));
    }

    @Transactional
    public WorkoutDto update(Long id, WorkoutRequest r) {
        Workout w = ownedWorkout(id);
        w.setName(r.name());
        w.setNotes(r.notes());
        return WorkoutMapper.toDto(w);
    }

    @Transactional
    public void delete(Long id) {
        if (access.current().role() == Role.COLLABORATOR) throw new ForbiddenException("Los colaboradores no pueden borrar rutinas");
        Workout w = ownedWorkout(id);
        if (assignments.countByWorkoutId(id) > 0) {
            throw new ConflictException("La rutina tiene asignaciones. Quítalas antes de borrarla");
        }
        items.deleteByWorkoutId(id);
        workouts.delete(w);
    }

    // ---------- Ejercicios dentro de la rutina ----------

    @Transactional
    public WorkoutDetailDto addItem(Long workoutId, WorkoutItemRequest r) {
        Workout w = ownedWorkout(workoutId);
        Exercise e = visibleExercise(r.exerciseId());
        WorkoutItem i = new WorkoutItem();
        i.setWorkoutId(w.getId());
        i.setPosition((int) items.countByWorkoutId(w.getId()) + 1);
        apply(i, e, r);
        items.save(i);
        return WorkoutMapper.toDetail(w, itemDtos(w));
    }

    @Transactional
    public WorkoutDetailDto updateItem(Long workoutId, Long itemId, WorkoutItemRequest r) {
        Workout w = ownedWorkout(workoutId);
        WorkoutItem i = ownedItem(w, itemId);
        apply(i, visibleExercise(r.exerciseId()), r);
        return WorkoutMapper.toDetail(w, itemDtos(w));
    }

    @Transactional
    public WorkoutDetailDto removeItem(Long workoutId, Long itemId) {
        Workout w = ownedWorkout(workoutId);
        items.delete(ownedItem(w, itemId));
        renumber(w.getId());
        return WorkoutMapper.toDetail(w, itemDtos(w));
    }

    // ---------- Asignaciones ----------

    @Transactional
    public AssignmentDto assign(AssignRequest r) {
        Workout w = workouts.findById(r.workoutId()).orElseThrow(() -> new NotFoundException("Entrenamiento no encontrado"));
        access.assertOwnerOrAdmin(w.getTrainerId());
        access.assertCanAccessClient(r.clientId());
        if (access.current().role() == Role.COLLABORATOR)
            access.assertPermission(access.collaborator().isCanAssignRoutines(), "asignar rutinas");
        WorkoutAssignment a = new WorkoutAssignment();
        a.setWorkoutId(w.getId());
        a.setClientId(r.clientId());
        a.setScheduledDate(r.scheduledDate());
        return WorkoutMapper.toDto(assignments.save(a), w);
    }

    @Transactional(readOnly = true)
    public Page<AssignmentDto> forClient(Long clientId, Pageable p) {
        access.assertCanAccessClient(clientId);
        return assignments.findByClientId(clientId, p).map(a -> WorkoutMapper.toDto(a, workouts.findById(a.getWorkoutId()).orElseThrow()));
    }

    /** Seguimiento: solo el cliente asignado marca la rutina como completada. */
    @Transactional
    public AssignmentDto complete(Long assignmentId) {
        WorkoutAssignment a = assignments.findById(assignmentId).orElseThrow(() -> new NotFoundException("Asignación no encontrada"));
        AuthUser me = access.current();
        if (me.role() != Role.CLIENT || !me.id().equals(a.getClientId())) throw new ForbiddenException("Solo el cliente asignado puede completarlo");
        a.setCompletedDate(LocalDate.now());
        publisher.publishEvent(new ClientActivityEvent(a.getClientId()));
        return WorkoutMapper.toDto(a, workouts.findById(a.getWorkoutId()).orElseThrow());
    }

    /**
     * Cumplimiento: compara cada ejercicio planificado (series x repeticiones x peso)
     * con las series realmente registradas en esa asignación.
     */
    @Transactional(readOnly = true)
    public ComplianceDto compliance(Long assignmentId) {
        WorkoutAssignment a = assignments.findById(assignmentId).orElseThrow(() -> new NotFoundException("Asignación no encontrada"));
        Workout w = workouts.findById(a.getWorkoutId()).orElseThrow();
        AuthUser me = access.current();
        if (me.role() == Role.CLIENT) {
            if (!me.id().equals(a.getClientId())) throw new ForbiddenException("Asignación de otro cliente");
        } else {
            access.assertCanAccessClient(a.getClientId());
        }

        List<WorkoutItem> plan = items.findByWorkoutIdOrderByPositionAsc(w.getId());
        List<ExerciseLog> done = logs.findByAssignmentId(assignmentId);
        List<ComplianceItemDto> out = new ArrayList<>();
        int sum = 0;
        for (WorkoutItem i : plan) {
            Exercise e = exercises.findById(i.getExerciseId()).orElseThrow();
            List<ExerciseLog> mine = done.stream().filter(l -> i.getId().equals(l.getWorkoutItemId())).toList();
            int maxReps = mine.stream().mapToInt(ExerciseLog::getReps).max().orElse(0);
            BigDecimal maxWeight = mine.stream().map(ExerciseLog::getWeightKg).max(Comparator.naturalOrder()).orElse(null);
            BigDecimal volume = mine.stream()
                    .map(l -> TrainingMath.volume(l.getWeightKg(), l.getReps()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int pct = TrainingMath.percent(mine.size(), i.getSets());
            sum += pct;
            out.add(new ComplianceItemDto(i.getId(), e.getId(), e.getName(), e.getMuscleGroup(), i.getSets(), i.getReps(),
                    i.getWeightKg(), mine.size(), maxReps, maxWeight, volume, pct));
        }
        int overall = plan.isEmpty() ? 0 : sum / plan.size();
        return new ComplianceDto(a.getId(), w.getId(), w.getName(), a.getClientId(), a.getScheduledDate(),
                a.getCompletedDate(), overall, out);
    }

    // ---------- Helpers ----------

    private Workout ownedWorkout(Long id) {
        Workout w = workouts.findById(id).orElseThrow(() -> new NotFoundException("Entrenamiento no encontrado"));
        access.assertOwnerOrAdmin(w.getTrainerId());
        return w;
    }

    private WorkoutItem ownedItem(Workout w, Long itemId) {
        WorkoutItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("Ejercicio de la rutina no encontrado"));
        if (!i.getWorkoutId().equals(w.getId())) throw new NotFoundException("Ejercicio de la rutina no encontrado");
        return i;
    }

    /** Ejercicios globales o propios del entrenador (o cualquiera si es ADMIN). */
    private Exercise visibleExercise(Long exerciseId) {
        Exercise e = exercises.findById(exerciseId).orElseThrow(() -> new NotFoundException("Ejercicio no encontrado"));
        AuthUser me = access.current();
        if (e.getOwnerId() != null && me.role() != Role.ADMIN && !access.workspaceId().equals(e.getOwnerId())) {
            throw new ForbiddenException("Ejercicio de otro entrenador");
        }
        return e;
    }

    private static void apply(WorkoutItem i, Exercise e, WorkoutItemRequest r) {
        i.setExerciseId(e.getId());
        i.setSets(r.sets());
        i.setReps(r.reps());
        i.setWeightKg(r.weightKg());
        i.setRestSeconds(r.restSeconds());
        i.setNotes(r.notes());
    }

    private List<WorkoutItemDto> itemDtos(Workout w) {
        return items.findByWorkoutIdOrderByPositionAsc(w.getId()).stream()
                .map(i -> WorkoutMapper.toItemDto(i, exercises.findById(i.getExerciseId()).orElseThrow()))
                .toList();
    }

    /** Tras borrar un ejercicio, las posiciones vuelven a quedar 1..n sin huecos. */
    private void renumber(Long workoutId) {
        int pos = 1;
        for (WorkoutItem i : items.findByWorkoutIdOrderByPositionAsc(workoutId)) i.setPosition(pos++);
    }
}
