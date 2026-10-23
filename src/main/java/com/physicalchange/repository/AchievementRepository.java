package com.physicalchange.repository;

import com.physicalchange.domain.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {
    List<Achievement> findByCategoryIdInOrderByPositionAscIdAsc(Collection<Long> categoryIds);
    List<Achievement> findByCategoryId(Long categoryId);
}
