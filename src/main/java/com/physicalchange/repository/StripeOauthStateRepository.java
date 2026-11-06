package com.physicalchange.repository;

import com.physicalchange.domain.StripeOauthState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface StripeOauthStateRepository extends JpaRepository<StripeOauthState, String> {
    /** Con bloqueo: dos vueltas simultáneas con el mismo state no pueden vincular dos veces (ADR-018, punto 7). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StripeOauthState s where s.state = :state")
    Optional<StripeOauthState> lockByState(@Param("state") String state);
}
