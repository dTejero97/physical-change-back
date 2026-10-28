package com.physicalchange.repository;

import com.physicalchange.domain.LibraryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface LibraryItemRepository extends JpaRepository<LibraryItem, Long> {
    List<LibraryItem> findByTrainerIdAndKindOrderByCreatedAtDesc(Long trainerId, String kind);
    long countByTrainerIdAndKind(Long trainerId, String kind);
    /** ¿Otro elemento ya usa ese archivo? */
    @Query("select count(i) > 0 from LibraryItem i join i.fileIds f where f = :fileId and i.id <> :itemId")
    boolean fileUsedElsewhere(@Param("fileId") Long fileId, @Param("itemId") Long itemId);
}
