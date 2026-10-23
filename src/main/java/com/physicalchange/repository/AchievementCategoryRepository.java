package com.physicalchange.repository;

import com.physicalchange.domain.AchievementCategory;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AchievementCategoryRepository extends JpaRepository<AchievementCategory, Long> {
    /** Las propias del profesional primero (por creación) y después las del sistema. */
    @Query("select c from AchievementCategory c where c.trainerId = :t or c.trainerId is null " +
           "order by case when c.trainerId is null then 1 else 0 end, c.position, c.id")
    List<AchievementCategory> findVisibleTo(@Param("t") Long trainerId);
    long countByTrainerId(Long trainerId);
}
