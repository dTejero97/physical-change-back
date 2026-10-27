package com.physicalchange.repository;

import com.physicalchange.domain.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    List<SubscriptionPlan> findByTrainerIdAndArchived(Long trainerId, boolean archived);
}
