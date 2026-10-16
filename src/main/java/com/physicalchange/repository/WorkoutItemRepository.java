package com.physicalchange.repository;

import com.physicalchange.domain.WorkoutItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkoutItemRepository extends JpaRepository<WorkoutItem, Long> {
    List<WorkoutItem> findByWorkoutIdOrderByPositionAsc(Long workoutId);
    long countByWorkoutId(Long workoutId);
    void deleteByWorkoutId(Long workoutId);
}
