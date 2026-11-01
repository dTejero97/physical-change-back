package com.physicalchange.repository;

import com.physicalchange.domain.LoadingBackground;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LoadingBackgroundRepository extends JpaRepository<LoadingBackground, Long> {
    Optional<LoadingBackground> findByBuiltinKey(String key);
    List<LoadingBackground> findAllByOrderByCreatedAtDescIdDesc();
}
