package com.physicalchange.repository;

import com.physicalchange.domain.AiMessage;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);
    @Modifying void deleteByConversationIdIn(Collection<Long> conversationIds);
}
