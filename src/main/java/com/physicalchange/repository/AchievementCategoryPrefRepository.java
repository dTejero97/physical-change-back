package com.physicalchange.repository;

import com.physicalchange.domain.AchievementCategoryPref;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AchievementCategoryPrefRepository extends JpaRepository<AchievementCategoryPref, AchievementCategoryPref.Key> {
    List<AchievementCategoryPref> findByTrainerId(Long trainerId);
}
