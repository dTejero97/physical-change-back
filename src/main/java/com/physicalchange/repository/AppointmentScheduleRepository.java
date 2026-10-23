package com.physicalchange.repository;

import com.physicalchange.domain.AppointmentSchedule;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AppointmentScheduleRepository extends JpaRepository<AppointmentSchedule, Long> {
    List<AppointmentSchedule> findByTrainerIdOrderByCreatedAtDesc(Long trainerId);
    long countBySessionTypeId(Long sessionTypeId);

    @Modifying
    @Query("update AppointmentSchedule s set s.sessionTypeId = :to where s.sessionTypeId = :from")
    int replaceSessionType(@Param("from") Long from, @Param("to") Long to);
}
