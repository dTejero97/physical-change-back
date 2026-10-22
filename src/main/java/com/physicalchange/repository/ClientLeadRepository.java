package com.physicalchange.repository;

import com.physicalchange.domain.ClientLead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClientLeadRepository extends JpaRepository<ClientLead, Long> {
    List<ClientLead> findByTrainerIdOrderByRegisteredAtDesc(Long trainerId);
    List<ClientLead> findAllByOrderByRegisteredAtDesc();
}
