package com.physicalchange.repository;

import com.physicalchange.domain.AiUsage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    /** Créditos gastados por un profesional desde una fecha (el mes en curso). */
    @Query("select coalesce(sum(u.credits), 0) from AiUsage u where u.trainerId = :t and u.createdAt >= :since")
    long creditsSince(@Param("t") Long trainerId, @Param("since") LocalDateTime since);

    /** Neuronas de toda la plataforma desde una fecha (presupuesto diario). */
    @Query("select coalesce(sum(u.neurons), 0) from AiUsage u where u.createdAt >= :since")
    long neuronsSince(@Param("since") LocalDateTime since);

    long countByTrainerIdAndKindAndCreatedAtGreaterThanEqual(Long trainerId, String kind, LocalDateTime since);
}
