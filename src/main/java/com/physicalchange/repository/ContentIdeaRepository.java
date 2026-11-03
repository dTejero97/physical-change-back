package com.physicalchange.repository;

import com.physicalchange.domain.ContentIdea;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ContentIdeaRepository extends JpaRepository<ContentIdea, Long> {
    /**
     * Ideas propias (bank = false, owner) o del banco (bank = true). cast(... as ...): con el parámetro nulo PostgreSQL
     * lo tomaba como bytea (QA, T48). Los filtros sí/no van como "anyX" + valor, nunca null: cast(null as Boolean) también
     * llegaba como bytea en PostgreSQL (prueba de humo, 2026-11-03). tag: patrón ",etiqueta," en minúsculas sobre ",tags,".
     */
    @Query("select i from ContentIdea i where ((:bank = true and i.ownerId is null) or (:bank = false and i.ownerId = :owner)) " +
           "and (cast(:format as String) is null or i.format = cast(:format as String)) " +
           "and (cast(:objective as String) is null or i.objective = cast(:objective as String)) " +
           "and (cast(:theme as String) is null or lower(i.theme) = lower(cast(:theme as String))) " +
           "and (cast(:tag as String) is null or lower(concat(',', coalesce(i.tags, ''), ',')) like cast(:tag as String)) " +
           "and (cast(:q as String) is null or lower(i.title) like cast(:q as String) or lower(coalesce(i.hook, '')) like cast(:q as String) " +
           "     or lower(coalesce(i.script, '')) like cast(:q as String)) " +
           "and (:anyFavorite = true or i.favorite = :favorite) " +
           "and (:anyUsed = true or i.used = :used)")
    Page<ContentIdea> search(@Param("bank") boolean bank, @Param("owner") Long owner, @Param("format") String format,
                             @Param("objective") String objective, @Param("theme") String theme, @Param("tag") String tag,
                             @Param("q") String q, @Param("anyFavorite") boolean anyFavorite, @Param("favorite") boolean favorite,
                             @Param("anyUsed") boolean anyUsed, @Param("used") boolean used, Pageable p);

    Optional<ContentIdea> findByOwnerIdAndSourceId(Long ownerId, Long sourceId);

    @Query("select i from ContentIdea i where i.ownerId = :owner and i.used = false order by i.favorite desc, i.updatedAt desc, i.id desc")
    List<ContentIdea> unusedOwn(@Param("owner") Long owner, Pageable p);

    @Query("select i from ContentIdea i where i.ownerId is null order by i.id")
    List<ContentIdea> bank(Pageable p);

    @Query("select distinct i.theme from ContentIdea i where i.ownerId is null and i.theme is not null order by i.theme")
    List<String> bankThemes();
}
