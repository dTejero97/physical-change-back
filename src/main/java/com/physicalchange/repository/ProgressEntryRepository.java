package com.physicalchange.repository;

import com.physicalchange.domain.ProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProgressEntryRepository extends JpaRepository<ProgressEntry, Long> {
    List<ProgressEntry> findByClientIdOrderByEntryDateAsc(Long clientId);
    /** Único por (cliente, día) desde V19. */
    Optional<ProgressEntry> findByClientIdAndEntryDate(Long clientId, LocalDate entryDate);
    List<ProgressEntry> findByClientIdAndEntryDateBetween(Long clientId, LocalDate from, LocalDate to);
}
