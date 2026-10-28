package com.physicalchange.repository;

import com.physicalchange.domain.ExerciseLog;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface ExerciseLogRepository extends JpaRepository<ExerciseLog, Long> {
    List<ExerciseLog> findByClientIdOrderByLogDateAscSetNumberAsc(Long clientId);
    List<ExerciseLog> findByAssignmentId(Long assignmentId);

    /** Última fecha con series registradas por cliente: filas [clientId, fecha]. */
    @Query("select l.clientId, max(l.logDate) from ExerciseLog l where l.clientId in :ids group by l.clientId")
    List<Object[]> lastLogDates(@Param("ids") java.util.Collection<Long> clientIds);

    @Query("select l from ExerciseLog l where l.clientId = :client and (cast(:exercise as Long) is null or l.exerciseId = :exercise) " +
           "and (cast(:from as LocalDate) is null or l.logDate >= :from) order by l.logDate asc, l.setNumber asc")
    List<ExerciseLog> search(@Param("client") Long clientId, @Param("exercise") Long exerciseId,
                             @Param("from") LocalDate from);
}
