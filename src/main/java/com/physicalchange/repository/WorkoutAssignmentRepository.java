package com.physicalchange.repository;

import com.physicalchange.domain.WorkoutAssignment;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutAssignmentRepository extends JpaRepository<WorkoutAssignment, Long> {
    Page<WorkoutAssignment> findByClientId(Long clientId, Pageable p);
    long countByClientIdAndCompletedDateIsNotNull(Long clientId);
    long countByClientIdAndCompletedDateIsNull(Long clientId);
}
