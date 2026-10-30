package com.physicalchange.repository;

import com.physicalchange.domain.ClientDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ClientDailyLogRepository extends JpaRepository<ClientDailyLog, Long> {
    Optional<ClientDailyLog> findByClientIdAndLogDate(Long clientId, LocalDate logDate);
    List<ClientDailyLog> findByClientIdAndLogDateBetween(Long clientId, LocalDate from, LocalDate to);
}
