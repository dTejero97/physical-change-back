package com.physicalchange.repository;

import com.physicalchange.domain.Workout;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutRepository extends JpaRepository<Workout, Long> {
    Page<Workout> findByTrainerId(Long trainerId, Pageable p);
}
