package com.physicalchange.repository;

import com.physicalchange.domain.ClientProfile;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ClientProfileRepository extends JpaRepository<ClientProfile, Long> {
    List<ClientProfile> findByClientIdIn(Collection<Long> clientIds);

    /** Número de clientes no eliminados con cada etiqueta: filas [tagId, total]. */
    @Query("select t, count(p) from ClientProfile p join p.tagIds t where p.removedAt is null and t in :tags group by t")
    List<Object[]> countByTags(@Param("tags") Collection<Long> tagIds);

    @Query("select p from ClientProfile p join p.tagIds t where t = :tag")
    List<ClientProfile> findByTag(@Param("tag") Long tagId);
}
