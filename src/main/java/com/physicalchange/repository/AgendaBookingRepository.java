package com.physicalchange.repository;

import com.physicalchange.domain.AgendaBooking;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AgendaBookingRepository extends JpaRepository<AgendaBooking, Long> {
    List<AgendaBooking> findByEventIdOrderByCreatedAtAsc(Long eventId);
    List<AgendaBooking> findByEventIdIn(Collection<Long> eventIds);
    Optional<AgendaBooking> findByEventIdAndClientId(Long eventId, Long clientId);
    List<AgendaBooking> findByClientIdAndStatus(Long clientId, String status);
    Optional<AgendaBooking> findFirstByEventIdAndStatusOrderByCreatedAtAscIdAsc(Long eventId, String status);
    long countByEventIdAndStatusIn(Long eventId, Collection<String> statuses);
    @Modifying void deleteByEventId(Long eventId);
}
