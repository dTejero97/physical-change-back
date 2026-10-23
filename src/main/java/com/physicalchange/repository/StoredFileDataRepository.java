package com.physicalchange.repository;

import com.physicalchange.domain.StoredFileData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileDataRepository extends JpaRepository<StoredFileData, Long> {}
