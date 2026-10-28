package com.physicalchange.repository;

import com.physicalchange.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    Optional<UserToken> findByTokenHash(String tokenHash);

    /** Al pedir un enlace nuevo, los anteriores del mismo tipo dejan de valer. */
    @Modifying
    @Query("update UserToken t set t.usedAt = CURRENT_TIMESTAMP where t.userId = :user and t.purpose = :purpose and t.usedAt is null")
    void invalidate(@Param("user") Long userId, @Param("purpose") String purpose);
}
