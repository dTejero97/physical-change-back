package com.physicalchange.repository;

import com.physicalchange.domain.ClientTag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClientTagRepository extends JpaRepository<ClientTag, Long> {
    List<ClientTag> findByTrainerIdOrderByNameAsc(Long trainerId);
    List<ClientTag> findAllByOrderByNameAsc();
    Optional<ClientTag> findByTrainerIdAndNameIgnoreCase(Long trainerId, String name);
}
