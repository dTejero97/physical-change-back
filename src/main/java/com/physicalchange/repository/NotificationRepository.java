package com.physicalchange.repository;

import com.physicalchange.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable p);
    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId, Pageable p);
    long countByUserIdAndReadAtIsNull(Long userId);
    long countByUserIdAndType(Long userId, String type);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :user and n.readAt is null")
    int markAllRead(@Param("user") Long userId, @Param("now") LocalDateTime now);
}
