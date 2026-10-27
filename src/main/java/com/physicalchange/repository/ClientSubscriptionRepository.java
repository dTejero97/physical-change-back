package com.physicalchange.repository;

import com.physicalchange.domain.ClientSubscription;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.*;

public interface ClientSubscriptionRepository extends JpaRepository<ClientSubscription, Long> {
    List<ClientSubscription> findByTrainerIdOrderByCreatedAtDesc(Long trainerId);
    boolean existsByClientIdAndPlanIdAndStatus(Long clientId, Long planId, String status);
    boolean existsByPlanId(Long planId);
    /** Activas con algún periodo ya vencido sin comprobante (puesta al día, ADR-004 §4). */
    List<ClientSubscription> findByTrainerIdAndStatusAndNextPeriodStartLessThanEqual(Long trainerId, String status, LocalDate day);

    @Query("select s.planId, count(s) from ClientSubscription s where s.status = 'ACTIVE' and s.planId in :ids group by s.planId")
    List<Object[]> countActiveByPlan(@Param("ids") Collection<Long> planIds);
}
