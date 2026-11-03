package com.physicalchange.repository;

import com.physicalchange.domain.SocialAccount;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    List<SocialAccount> findByOwnerId(Long ownerId);
    Optional<SocialAccount> findByOwnerIdAndNetwork(Long ownerId, String network);
    boolean existsByOwnerIdAndNetwork(Long ownerId, String network);
    List<SocialAccount> findByStatus(String status);

    /** Claim de la sincronización (ADR-014 §6): solo una instancia la toma; una toma de hace más de staleBefore se da por perdida. */
    @Modifying
    @Query("update SocialAccount a set a.syncStartedAt = :now where a.id = :id and (a.syncStartedAt is null or a.syncStartedAt < :staleBefore)")
    int claimSync(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
