package com.physicalchange.repository;

import com.physicalchange.domain.LibraryForm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LibraryFormRepository extends JpaRepository<LibraryForm, Long> {
    List<LibraryForm> findByTrainerIdOrderByUpdatedAtDesc(Long trainerId);
}
