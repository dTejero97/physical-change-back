package com.physicalchange.repository;

import com.physicalchange.domain.Workout;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutRepository extends JpaRepository<Workout, Long> {
    /** Librería del profesional: sin las copias de una sola sesión. */
    Page<Workout> findByTrainerIdAndSessionCopyOfIsNull(Long trainerId, Pageable p);
}
