package com.physicalchange.repository;

import com.physicalchange.domain.LoadingBackgroundVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoadingBackgroundVariantRepository extends JpaRepository<LoadingBackgroundVariant, Long> {
    List<LoadingBackgroundVariant> findByBackgroundIdOrderByIdAsc(Long backgroundId);
    List<LoadingBackgroundVariant> findByBackgroundIdIn(Collection<Long> backgroundIds);
    Optional<LoadingBackgroundVariant> findByBackgroundIdAndVariant(Long backgroundId, String variant);
}
