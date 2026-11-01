package com.physicalchange.repository;

import com.physicalchange.domain.ContentCollectionItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ContentCollectionItemRepository extends JpaRepository<ContentCollectionItem, ContentCollectionItem.Key> {
    List<ContentCollectionItem> findByCollectionIdOrderByAddedAtDesc(Long collectionId);
    long countByCollectionId(Long collectionId);

    @Modifying
    @Query("delete from ContentCollectionItem i where i.collectionId = :c")
    int deleteByCollection(@Param("c") Long collectionId);

    /** Al borrar una idea, un gancho o un copy (item_id polimórfico, sin clave ajena). */
    @Modifying
    @Query("delete from ContentCollectionItem i where i.itemType = :type and i.itemId = :id")
    int deleteByItem(@Param("type") String type, @Param("id") Long id);
}
