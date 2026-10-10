package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

@Service
public class WorkoutService {
    private final WorkoutRepository workouts; private final WorkoutAssignmentRepository assignments; private final AccessService access;
    public WorkoutService(WorkoutRepository w, WorkoutAssignmentRepository a, AccessService ac) { workouts = w; assignments = a; access = ac; }

    private static WorkoutDto dto(Workout w) { return new WorkoutDto(w.getId(), w.getName(), w.getNotes()); }

    @Transactional(readOnly = true)
    public Page<WorkoutDto> list(Pageable p) { return workouts.findByTrainerId(access.current().id(), p).map(WorkoutService::dto); }

    @Transactional
    public WorkoutDto create(WorkoutRequest r) {
        Workout w = new Workout(); w.setTrainerId(access.current().id()); w.setName(r.name()); w.setNotes(r.notes());
        return dto(workouts.save(w));
    }

    @Transactional
    public AssignmentDto assign(AssignRequest r) {
        Workout w = workouts.findById(r.workoutId()).orElseThrow(() -> new NotFoundException("Entrenamiento no encontrado"));
        access.assertOwnerOrAdmin(w.getTrainerId());
        access.assertCanAccessClient(r.clientId());
        WorkoutAssignment a = new WorkoutAssignment();
        a.setWorkoutId(w.getId()); a.setClientId(r.clientId()); a.setScheduledDate(r.scheduledDate());
        return toDto(assignments.save(a), w);
    }

    private AssignmentDto toDto(WorkoutAssignment a, Workout w) {
        return new AssignmentDto(a.getId(), a.getWorkoutId(), w.getName(), a.getClientId(), a.getScheduledDate(), a.getCompletedDate());
    }

    @Transactional(readOnly = true)
    public Page<AssignmentDto> forClient(Long clientId, Pageable p) {
        access.assertCanAccessClient(clientId);
        return assignments.findByClientId(clientId, p).map(a -> toDto(a, workouts.findById(a.getWorkoutId()).orElseThrow()));
    }

    /** Seguimiento: solo el cliente propietario marca como completado. */
    @Transactional
    public AssignmentDto complete(Long assignmentId) {
        WorkoutAssignment a = assignments.findById(assignmentId).orElseThrow(() -> new NotFoundException("Asignación no encontrada"));
        AuthUser me = access.current();
        if (me.role() != Role.CLIENT || !me.id().equals(a.getClientId())) throw new ForbiddenException("Solo el cliente asignado puede completarlo");
        a.setCompletedDate(LocalDate.now());
        return toDto(a, workouts.findById(a.getWorkoutId()).orElseThrow());
    }
}
