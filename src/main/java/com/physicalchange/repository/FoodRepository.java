package com.physicalchange.repository;

import com.physicalchange.domain.Food;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FoodRepository extends JpaRepository<Food, Long> {
    /** cast(... as String): con el parámetro nulo PostgreSQL lo tomaba como bytea y fallaba "lower(bytea)" (QA, T98). */
    @Query("select f from Food f where cast(:q as String) is null or lower(f.name) like lower(concat('%', cast(:q as String), '%'))")
    Page<Food> search(@Param("q") String q, Pageable p);
}
