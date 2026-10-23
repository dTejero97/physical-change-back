package com.physicalchange.repository;

import com.physicalchange.domain.LibraryDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LibraryDocumentRepository extends JpaRepository<LibraryDocument, Long> {
    List<LibraryDocument> findByTrainerIdOrderByCreatedAtDesc(Long trainerId);
    boolean existsByFileId(Long fileId);
}
