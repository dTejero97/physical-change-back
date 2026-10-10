package com.physicalchange.repository;

import com.physicalchange.domain.Exercise;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ExerciseRepository extends JpaRepository<Exercise, Long> {
    @Query("select e from Exercise e where (e.ownerId is null or e.ownerId = :owner) " +
           "and (:q is null or lower(e.name) like lower(concat('%', :q, '%'))) " +
           "and (:muscle is null or e.muscleGroup = :muscle)")
    Page<Exercise> search(@Param("owner") Long owner, @Param("q") String q,
                          @Param("muscle") String muscle, Pageable p);
}
