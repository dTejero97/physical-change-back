package com.physicalchange.repository;

import com.physicalchange.domain.AchievementHidden;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AchievementHiddenRepository extends JpaRepository<AchievementHidden, AchievementHidden.Key> {
    List<AchievementHidden> findByTrainerId(Long trainerId);
}
