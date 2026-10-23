package com.physicalchange.repository;

import com.physicalchange.domain.AgendaEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface AgendaEventRepository extends JpaRepository<AgendaEvent, Long> {
    /** Eventos que empiezan en [from, to). */
    List<AgendaEvent> findByTrainerIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(Long trainerId, LocalDateTime from, LocalDateTime to);
    List<AgendaEvent> findBySeriesKeyAndStartAtGreaterThanEqualOrderByStartAtAsc(String seriesKey, LocalDateTime from);
    long countBySessionTypeId(Long sessionTypeId);

    /** Sustituir tipo de sesión: solo eventos desde la fecha indicada. */
    @Modifying
    @Query("update AgendaEvent e set e.sessionTypeId = :to where e.sessionTypeId = :from and e.startAt >= :since")
    int replaceSessionType(@Param("from") Long from, @Param("to") Long to, @Param("since") LocalDateTime since);

    /** Sesiones en las que el cliente tiene una reserva con alguno de los estados dados (calendario del cliente). */
    @Query("select e from AgendaEvent e, AgendaBooking b where b.eventId = e.id and b.clientId = :c and b.status in :st " +
           "and e.startAt >= :from and e.startAt < :to order by e.startAt")
    List<AgendaEvent> findBookedByClient(@Param("c") Long clientId, @Param("st") Collection<String> statuses,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
