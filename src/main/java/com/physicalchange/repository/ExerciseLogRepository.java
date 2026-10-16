package com.physicalchange.repository;

import com.physicalchange.domain.ExerciseLog;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface ExerciseLogRepository extends JpaRepository<ExerciseLog, Long> {
    List<ExerciseLog> findByClientIdOrderByLogDateAscSetNumberAsc(Long clientId);
    List<ExerciseLog> findByAssignmentId(Long assignmentId);

    @Query("select l from ExerciseLog l where l.clientId = :client and (:exercise is null or l.exerciseId = :exercise) " +
           "and (:from is null or l.logDate >= :from) order by l.logDate asc, l.setNumber asc")
    List<ExerciseLog> search(@Param("client") Long clientId, @Param("exercise") Long exerciseId,
                             @Param("from") LocalDate from);
}
