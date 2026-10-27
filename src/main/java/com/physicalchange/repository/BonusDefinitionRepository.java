package com.physicalchange.repository;

import com.physicalchange.domain.BonusDefinition;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface BonusDefinitionRepository extends JpaRepository<BonusDefinition, Long> {
    List<BonusDefinition> findByProductIdIn(Collection<Long> productIds);
    List<BonusDefinition> findByPlanIdIn(Collection<Long> planIds);
    @Modifying void deleteByProductId(Long productId);
    @Modifying void deleteByPlanId(Long planId);
}
