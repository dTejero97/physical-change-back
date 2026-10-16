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

    /** Asignaciones de las rutinas de un entrenador (la rutina es la que tiene el dueño). */
    @Query("select a from WorkoutAssignment a, Workout w where a.workoutId = w.id and w.trainerId = :t")
    List<WorkoutAssignment> findByTrainer(@Param("t") Long trainerId);
}
