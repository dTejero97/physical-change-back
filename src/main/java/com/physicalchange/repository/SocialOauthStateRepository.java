package com.physicalchange.repository;

import com.physicalchange.domain.SocialOauthState;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface SocialOauthStateRepository extends JpaRepository<SocialOauthState, Long> {
    Optional<SocialOauthState> findByStateHash(String stateHash);

    /** Consume el state una sola vez: 1 = lo hemos usado nosotros; 0 = ya estaba usado. Borra a la vez el code_verifier. */
    @Modifying(clearAutomatically = true)
    @Query("update SocialOauthState s set s.usedAt = :now, s.codeVerifierEnc = null where s.id = :id and s.usedAt is null")
    int consume(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("delete from SocialOauthState s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
