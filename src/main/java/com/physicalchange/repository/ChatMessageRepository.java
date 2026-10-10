package com.physicalchange.repository;

import com.physicalchange.domain.ChatMessage;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Query("select m from ChatMessage m where (m.senderId = :a and m.recipientId = :b) " +
           "or (m.senderId = :b and m.recipientId = :a) order by m.sentAt desc")
    Page<ChatMessage> conversation(@Param("a") Long a, @Param("b") Long b, Pageable p);
}
