package com.physicalchange.repository;

import com.physicalchange.domain.LibraryProgramItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface LibraryProgramItemRepository extends JpaRepository<LibraryProgramItem, Long> {
    List<LibraryProgramItem> findByProgramIdOrderByWeekAscDayAscPositionAsc(Long programId);
    @Modifying void deleteByProgramId(Long programId);

    /** Número de elementos por programa: filas [programId, total]. */
    @Query("select i.programId, count(i) from LibraryProgramItem i where i.programId in :ids group by i.programId")
    List<Object[]> countByPrograms(@Param("ids") Collection<Long> programIds);
}
