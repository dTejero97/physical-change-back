package com.physicalchange.repository;

import com.physicalchange.domain.LibraryFormQuestion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface LibraryFormQuestionRepository extends JpaRepository<LibraryFormQuestion, Long> {
    List<LibraryFormQuestion> findByFormIdOrderByPositionAsc(Long formId);
    @Modifying void deleteByFormId(Long formId);

    /** Número de preguntas por formulario: filas [formId, total]. */
    @Query("select q.formId, count(q) from LibraryFormQuestion q where q.formId in :ids group by q.formId")
    List<Object[]> countByForms(@Param("ids") Collection<Long> formIds);
}
