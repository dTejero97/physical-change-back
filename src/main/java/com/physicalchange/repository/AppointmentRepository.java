package com.physicalchange.repository;

import com.physicalchange.domain.Appointment;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    Page<Appointment> findByClientId(Long clientId, Pageable p);
    Page<Appointment> findByTrainerId(Long trainerId, Pageable p);
    List<Appointment> findByTrainerIdAndStartAtBetween(Long trainerId, LocalDateTime from, LocalDateTime to);
    List<Appointment> findByClientIdAndStartAtBetween(Long clientId, LocalDateTime from, LocalDateTime to);
    List<Appointment> findByClientIdInAndStartAtBetween(Collection<Long> clientIds, LocalDateTime from, LocalDateTime to);
    @Query("select count(a) from Appointment a where a.trainerId = :t and a.status = 'BOOKED' " +
           "and a.startAt < :end and a.endAt > :start")
    long countOverlaps(@Param("t") Long trainerId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    long countByTrainerIdAndStatusAndStartAtAfter(Long trainerId, String status, LocalDateTime from);
    long countByTrainerIdAndStatus(Long trainerId, String status);
}
