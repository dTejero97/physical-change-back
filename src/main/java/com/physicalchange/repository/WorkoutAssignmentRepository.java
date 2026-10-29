package com.physicalchange.repository;

import com.physicalchange.domain.WorkoutAssignment;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface WorkoutAssignmentRepository extends JpaRepository<WorkoutAssignment, Long> {
    Page<WorkoutAssignment> findByClientId(Long clientId, Pageable p);
    List<WorkoutAssignment> findByClientIdAndScheduledDateBetween(Long clientId, LocalDate from, LocalDate to);
    List<WorkoutAssignment> findByClientIdInAndScheduledDateBetween(Collection<Long> clientIds, LocalDate from, LocalDate to);
    long countByClientIdAndCompletedDateIsNotNull(Long clientId);
    long countByClientIdAndCompletedDateIsNull(Long clientId);
    long countByWorkoutId(Long workoutId);
    /** Sesiones aún no completadas de un cliente con una rutina: a ellas se aplica un cambio aceptado (ADR-012 §2). */
    List<WorkoutAssignment> findByClientIdAndWorkoutIdAndCompletedDateIsNull(Long clientId, Long workoutId);
    List<WorkoutAssignment> findByClientId(Long clientId);

    /** Asignaciones completadas de un workout por ciertos clientes (ranking de comunidad). */
    List<WorkoutAssignment> findByWorkoutIdAndClientIdInAndCompletedDateIsNotNullOrderByCompletedDateAsc(Long workoutId, Collection<Long> clientIds);

    /** Última rutina completada por cliente: filas [clientId, fecha]. */
    @Query("select a.clientId, max(a.completedDate) from WorkoutAssignment a where a.clientId in :ids and a.completedDate is not null group by a.clientId")
    List<Object[]> lastCompletedDates(@Param("ids") Collection<Long> clientIds);

    /** Asignaciones de las rutinas de un entrenador (la rutina es la que tiene el dueño). */
    @Query("select a from WorkoutAssignment a, Workout w where a.workoutId = w.id and w.trainerId = :t")
    List<WorkoutAssignment> findByTrainer(@Param("t") Long trainerId);
}
