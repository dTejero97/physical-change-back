package com.physicalchange.repository;

import com.physicalchange.domain.AchievementUnlock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AchievementUnlockRepository extends JpaRepository<AchievementUnlock, Long> {
    List<AchievementUnlock> findByClientId(Long clientId);
    long countByClientId(Long clientId);
}
