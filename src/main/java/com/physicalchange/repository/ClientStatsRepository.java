package com.physicalchange.repository;

import com.physicalchange.domain.ClientStats;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ClientStatsRepository extends JpaRepository<ClientStats, Long> {
    /** Serializa las evaluaciones de logros del mismo cliente (ADR-012 §5). */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ClientStats s where s.clientId = :id")
    Optional<ClientStats> lockById(@Param("id") Long clientId);
}
