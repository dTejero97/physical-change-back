package com.physicalchange.repository;

import com.physicalchange.domain.SessionType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SessionTypeRepository extends JpaRepository<SessionType, Long> {
    List<SessionType> findByTrainerIdOrderByNameAsc(Long trainerId);
    Optional<SessionType> findByTrainerIdAndNameIgnoreCase(Long trainerId, String name);
    long countByTrainerId(Long trainerId);
}
