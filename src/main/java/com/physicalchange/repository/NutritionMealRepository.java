package com.physicalchange.repository;

import com.physicalchange.domain.NutritionMeal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NutritionMealRepository extends JpaRepository<NutritionMeal, Long> {
    List<NutritionMeal> findByPlanIdOrderByMealNameAscPositionAsc(Long planId);
}
