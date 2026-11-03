package com.physicalchange.repository;

import com.physicalchange.domain.ContentSnippet;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ContentSnippetRepository extends JpaRepository<ContentSnippet, Long> {
    /** Ganchos o copys propios (bank = false) o del banco (bank = true). Mismo patrón de filtros que ContentIdeaRepository. */
    @Query("select s from ContentSnippet s where s.kind = :kind " +
           "and ((:bank = true and s.ownerId is null) or (:bank = false and s.ownerId = :owner)) " +
           "and (cast(:network as String) is null or s.network = cast(:network as String)) " +
           "and (cast(:objective as String) is null or s.objective = cast(:objective as String)) " +
           "and (cast(:theme as String) is null or lower(s.theme) = lower(cast(:theme as String))) " +
           "and (cast(:tag as String) is null or lower(concat(',', coalesce(s.tags, ''), ',')) like cast(:tag as String)) " +
           "and (cast(:q as String) is null or lower(s.text) like cast(:q as String) or lower(coalesce(s.cta, '')) like cast(:q as String)) " +
           "and (cast(:favorite as Boolean) is null or s.favorite = cast(:favorite as Boolean))")
    Page<ContentSnippet> search(@Param("kind") String kind, @Param("bank") boolean bank, @Param("owner") Long owner,
                                @Param("network") String network, @Param("objective") String objective, @Param("theme") String theme,
                                @Param("tag") String tag, @Param("q") String q, @Param("favorite") Boolean favorite, Pageable p);

    Optional<ContentSnippet> findByOwnerIdAndSourceId(Long ownerId, Long sourceId);

    @Query("select s from ContentSnippet s where s.ownerId = :owner and s.kind = :kind and s.favorite = true order by s.updatedAt desc, s.id desc")
    List<ContentSnippet> favorites(@Param("owner") Long owner, @Param("kind") String kind, Pageable p);

    @Query("select s from ContentSnippet s where s.ownerId is null and s.kind = :kind order by s.id")
    List<ContentSnippet> bank(@Param("kind") String kind, Pageable p);
}
