package com.physicalchange.repository;

import com.physicalchange.domain.PlatformServiceEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformServiceEntryRepository extends JpaRepository<PlatformServiceEntry, Long> {
}
