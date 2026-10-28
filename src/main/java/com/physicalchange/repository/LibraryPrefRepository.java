package com.physicalchange.repository;

import com.physicalchange.domain.LibraryPref;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryPrefRepository extends JpaRepository<LibraryPref, LibraryPref.Key> {}
