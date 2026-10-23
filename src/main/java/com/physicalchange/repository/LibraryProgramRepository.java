package com.physicalchange.repository;

import com.physicalchange.domain.LibraryProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LibraryProgramRepository extends JpaRepository<LibraryProgram, Long> {
    List<LibraryProgram> findByTrainerIdOrderByUpdatedAtDesc(Long trainerId);
    List<LibraryProgram> findByLinkedProgramId(Long programId);
}
