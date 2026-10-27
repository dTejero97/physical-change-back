package com.physicalchange.repository;

import com.physicalchange.domain.BonusRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BonusRedemptionRepository extends JpaRepository<BonusRedemption, Long> {
    Optional<BonusRedemption> findByBookingId(Long bookingId);
    /** "Bonos canjeados": canjes de las reservas de un conjunto de sesiones. */
    long countByBookingIdIn(Collection<Long> bookingIds);
    List<BonusRedemption> findByBookingIdIn(Collection<Long> bookingIds);
}
