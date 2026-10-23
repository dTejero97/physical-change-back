package com.physicalchange.repository;

import com.physicalchange.domain.StoredFile;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    /** Bytes ocupados por un propietario (cuota, ADR-001). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.ownerId = :o")
    long usedBytes(@Param("o") Long ownerId);
}
