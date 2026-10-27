package com.physicalchange.repository;

import com.physicalchange.domain.AiInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiInstructionRepository extends JpaRepository<AiInstruction, Long> {
}
