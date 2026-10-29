package com.physicalchange.repository;

import com.physicalchange.domain.ClientOnboarding;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ClientOnboardingRepository extends JpaRepository<ClientOnboarding, Long> {
    /** Serializa los guardados y el "completar" del mismo cliente (un solo aviso aunque lleguen dos a la vez). */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ClientOnboarding o where o.clientId = :id")
    Optional<ClientOnboarding> lockById(@Param("id") Long clientId);

    @Query("select case when count(o) > 0 then true else false end from ClientOnboarding o where o.clientId = :id and o.completedAt is not null")
    boolean isCompleted(@Param("id") Long clientId);
}
