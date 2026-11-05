package com.physicalchange.repository;

import com.physicalchange.domain.ProgramAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ProgramAssignmentRepository extends JpaRepository<ProgramAssignment, Long> {
    List<ProgramAssignment> findByClientIdOrderByCreatedAtDesc(Long clientId);
    boolean existsBySubscriptionId(Long subscriptionId);
    List<ProgramAssignment> findBySubscriptionIdIn(Collection<Long> subscriptionIds);
}
