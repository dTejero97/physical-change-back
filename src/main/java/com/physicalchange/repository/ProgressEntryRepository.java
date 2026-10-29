package com.physicalchange.repository;

import com.physicalchange.domain.ProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProgressEntryRepository extends JpaRepository<ProgressEntry, Long> {
    List<ProgressEntry> findByClientIdOrderByEntryDateAsc(Long clientId);
    List<ProgressEntry> findByClientIdAndEntryDateOrderByIdAsc(Long clientId, java.time.LocalDate entryDate);
}
