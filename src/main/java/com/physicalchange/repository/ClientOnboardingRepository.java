package com.physicalchange.repository;

import com.physicalchange.domain.ClientOnboarding;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ClientOnboardingRepository extends JpaRepository<ClientOnboarding, Long> {
    @Query("select case when count(o) > 0 then true else false end from ClientOnboarding o where o.clientId = :id and o.completedAt is not null")
    boolean isCompleted(@Param("id") Long clientId);
}
