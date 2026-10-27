package com.physicalchange.repository;

import com.physicalchange.domain.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    List<AiConversation> findByTrainerIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(Long trainerId, LocalDateTime since);
    List<AiConversation> findByUpdatedAtBefore(LocalDateTime limit);
}
