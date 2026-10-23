package com.physicalchange.repository;

import com.physicalchange.domain.AgendaActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AgendaActivityRepository extends JpaRepository<AgendaActivity, Long> {
    List<AgendaActivity> findByTrainerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(Long trainerId, LocalDateTime from, LocalDateTime to);
}
