package com.physicalchange.repository;

import com.physicalchange.domain.NutritionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NutritionPlanRepository extends JpaRepository<NutritionPlan, Long> {
    List<NutritionPlan> findByClientIdOrderByIdDesc(Long clientId);
    List<NutritionPlan> findByClientIdIn(java.util.Collection<Long> clientIds);
}
