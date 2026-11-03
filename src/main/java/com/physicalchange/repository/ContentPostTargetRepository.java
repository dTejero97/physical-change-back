package com.physicalchange.repository;

import com.physicalchange.domain.ContentPostTarget;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ContentPostTargetRepository extends JpaRepository<ContentPostTarget, Long> {
    /** Claim de una red automática (ADR-014 §6, paso 4): PENDING → PUBLISHING una sola vez. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ContentPostTarget t set t.status = 'PUBLISHING', t.claimedAt = :now, t.attempts = t.attempts + 1 " +
           "where t.id = :id and t.status = 'PENDING'")
    int claimPublishing(@Param("id") Long id, @Param("now") Instant now);

    /** Caída a mitad de una publicación: PUBLISHING desde hace más de 15 min → FAILED (nunca se reintenta solo). */
    @Query("select t.id from ContentPostTarget t where t.status = 'PUBLISHING' and t.claimedAt < :before")
    List<Long> stalePublishing(@Param("before") Instant before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ContentPostTarget t set t.status = 'FAILED', t.error = :error where t.id = :id and t.status = 'PUBLISHING'")
    int failStale(@Param("id") Long id, @Param("error") String error);

    /** Destinos publicados con publishedAt en [from, to) de un espacio (analíticas). */
    @Query("select t from ContentPostTarget t join fetch t.post p where p.ownerId = :owner and t.status = 'PUBLISHED' " +
           "and t.publishedAt >= :from and t.publishedAt < :to")
    List<ContentPostTarget> publishedBetween(@Param("owner") Long owner, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select t from ContentPostTarget t join fetch t.post p where p.ownerId = :owner and t.network = :network " +
           "and t.status = 'PUBLISHED' and t.externalId is not null")
    List<ContentPostTarget> publishedWithExternalId(@Param("owner") Long owner, @Param("network") String network);

    List<ContentPostTarget> findByIdIn(Collection<Long> ids);
}
