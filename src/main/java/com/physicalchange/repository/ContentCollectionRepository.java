package com.physicalchange.repository;

import com.physicalchange.domain.ContentCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContentCollectionRepository extends JpaRepository<ContentCollection, Long> {
    List<ContentCollection> findByOwnerIdOrderByNameAsc(Long ownerId);
    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(Long ownerId, String name, Long id);
}
