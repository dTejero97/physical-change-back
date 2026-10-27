package com.physicalchange.repository;

import com.physicalchange.domain.Coupon;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    /** Gasta un uso de forma atómica: 0 si está agotado (ADR-004 §3). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Coupon c set c.uses = c.uses + 1 where c.id = :id and (c.maxUses is null or c.uses < c.maxUses)")
    int consume(@Param("id") Long id);

    java.util.List<Coupon> findByTrainerIdOrderByCreatedAtDesc(Long trainerId);
    boolean existsByTrainerIdAndCodeAndIdNot(Long trainerId, String code, Long id);
    boolean existsByTrainerIdAndCode(Long trainerId, String code);
}
