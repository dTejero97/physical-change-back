package com.physicalchange.repository;

import com.physicalchange.domain.Exercise;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ExerciseRepository extends JpaRepository<Exercise, Long> {
    /** cast(... as String): con el parámetro nulo PostgreSQL lo tomaba como bytea y fallaba "lower(bytea)" (QA, T48). */
    @Query("select e from Exercise e where (e.ownerId is null or e.ownerId = :owner) " +
           "and (cast(:q as String) is null or lower(e.name) like lower(concat('%', cast(:q as String), '%'))) " +
           "and (cast(:muscle as String) is null or e.muscleGroup = cast(:muscle as String))")
    Page<Exercise> search(@Param("owner") Long owner, @Param("q") String q,
                          @Param("muscle") String muscle, Pageable p);

    /** Ejercicios globales o del propietario con ese nombre (sin distinguir mayúsculas). Copia de plantillas. */
    @Query("select e from Exercise e where (e.ownerId is null or e.ownerId = :owner) and lower(e.name) = lower(:name) order by e.id")
    java.util.List<Exercise> findVisibleByName(@Param("owner") Long owner, @Param("name") String name);
}
