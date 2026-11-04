package com.physicalchange.repository;

import com.physicalchange.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    /**
     * Filtros opcionales sin parámetros null que PostgreSQL no sepa tipar (T190): actor y cliente con cast a Long (ya usado en
     * ExerciseLogRepository), acciones con un indicador "todas" y una lista nunca vacía, texto con cast a String.
     */
    @Query("select a from AuditEvent a where a.occurredAt >= :from and a.occurredAt < :to " +
           "and (cast(:actor as Long) is null or a.actorId = :actor) " +
           "and (cast(:client as Long) is null or a.clientId = :client) " +
           "and (:allActions = true or a.action in :actions) " +
           "and (cast(:q as String) is null or lower(coalesce(a.summary, '')) like cast(:q as String)) " +
           "order by a.occurredAt desc, a.id desc")
    Page<AuditEvent> search(@Param("from") Instant from, @Param("to") Instant to, @Param("actor") Long actorId, @Param("client") Long clientId,
                            @Param("allActions") boolean allActions, @Param("actions") Collection<String> actions, @Param("q") String q,
                            Pageable p);

    @Modifying
    @Query("delete from AuditEvent a where a.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
