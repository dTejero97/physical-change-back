package com.physicalchange.repository;

import com.physicalchange.domain.ChangeRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {
    boolean existsByClientIdAndPendingKey(Long clientId, String pendingKey);

    @Query("select r from ChangeRequest r where r.clientId = :client and (cast(:status as String) is null or r.status = :status) " +
           "order by r.createdAt desc, r.id desc")
    List<ChangeRequest> findByClient(@Param("client") Long clientId, @Param("status") String status);

    @Query("select r from ChangeRequest r where r.professionalId = :pro and (cast(:status as String) is null or r.status = :status) " +
           "order by r.createdAt desc, r.id desc")
    List<ChangeRequest> findByProfessional(@Param("pro") Long professionalId, @Param("status") String status);

    @Query("select r from ChangeRequest r where (cast(:status as String) is null or r.status = :status) order by r.createdAt desc, r.id desc")
    List<ChangeRequest> findAllByStatus(@Param("status") String status);

    /** Bloquea la solicitud hasta el fin de la transacción: dos decisiones simultáneas no se pisan. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChangeRequest r where r.id = :id")
    Optional<ChangeRequest> lockById(@Param("id") Long id);

    /** Solicitudes pendientes de un cliente sobre una rutina (se marcan en la pantalla de rutinas). */
    List<ChangeRequest> findByClientIdAndStatus(Long clientId, String status);
}
