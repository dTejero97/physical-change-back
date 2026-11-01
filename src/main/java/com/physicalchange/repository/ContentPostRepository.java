package com.physicalchange.repository;

import com.physicalchange.domain.ContentPost;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long> {
    @Query("select p from ContentPost p where p.ownerId = :owner " +
           "and (cast(:status as String) is null or p.status = cast(:status as String)) " +
           "and (cast(:format as String) is null or p.format = cast(:format as String)) " +
           "and (cast(:network as String) is null or exists (select t.id from ContentPostTarget t where t.post = p and t.network = cast(:network as String))) " +
           "and (:dated = false or (p.scheduledAt >= :from and p.scheduledAt < :to)) " +
           "and (cast(:q as String) is null or lower(p.title) like cast(:q as String) or lower(coalesce(p.body, '')) like cast(:q as String))")
    Page<ContentPost> search(@Param("owner") Long owner, @Param("status") String status, @Param("format") String format,
                             @Param("network") String network, @Param("dated") boolean dated, @Param("from") Instant from,
                             @Param("to") Instant to, @Param("q") String q, Pageable p);

    /** Publicaciones con fecha en [from, to) y en alguno de esos estados (calendario y analíticas). */
    @Query("select p from ContentPost p where p.ownerId = :owner and p.status in :statuses and p.scheduledAt >= :from and p.scheduledAt < :to " +
           "order by p.scheduledAt asc, p.id asc")
    List<ContentPost> inRange(@Param("owner") Long owner, @Param("statuses") Collection<String> statuses,
                              @Param("from") Instant from, @Param("to") Instant to);

    /** Job: ids de publicaciones programadas cuya hora ya ha llegado (índice ix_content_post_due). */
    @Query("select p.id from ContentPost p where p.status = 'SCHEDULED' and p.scheduledAt <= :now order by p.scheduledAt asc, p.id asc")
    List<Long> dueIds(@Param("now") Instant now, Pageable p);

    /**
     * Claim (ADR-014 §6, paso 2): solo gana quien cambia la fila de SCHEDULED a DUE. Sube la versión para que una edición
     * concurrente de la misma publicación falle con 409 en vez de devolverla a SCHEDULED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ContentPost p set p.status = 'DUE', p.dueAt = :now, p.version = p.version + 1 where p.id = :id and p.status = 'SCHEDULED'")
    int claimDue(@Param("id") Long id, @Param("now") Instant now);

    /** DUE → FAILED una sola vez (el aviso CONTENT_POST_FAILED lo crea quien gana). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ContentPost p set p.status = 'FAILED', p.version = p.version + 1 where p.id = :id and p.status = 'DUE'")
    int markFailedFromDue(@Param("id") Long id);

    /** Publicaciones con alguna red en MANUAL_DUE ("toca publicar"): contador de la barra lateral. */
    @Query("select count(distinct t.post.id) from ContentPostTarget t where t.post.ownerId = :owner and t.status = 'MANUAL_DUE' and t.post.status in ('DUE', 'FAILED')")
    long countManualDue(@Param("owner") Long owner);

    /** Al borrar una idea: sus publicaciones la pierden (en PostgreSQL también ON DELETE SET NULL). */
    @Modifying
    @Query("update ContentPost p set p.ideaId = null where p.ideaId = :idea")
    int clearIdea(@Param("idea") Long ideaId);
}
