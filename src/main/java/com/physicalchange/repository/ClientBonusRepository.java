package com.physicalchange.repository;

import com.physicalchange.domain.ClientBonus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface ClientBonusRepository extends JpaRepository<ClientBonus, Long> {
    List<ClientBonus> findByClientIdOrderByExpiresAtAsc(Long clientId);
    List<ClientBonus> findByReceiptId(Long receiptId);
    boolean existsByReceiptId(Long receiptId);

    /** Bonos vigentes con saldo de un tipo de sesión, el que antes caduca primero, bloqueados para el canje (ADR-004 §5). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ClientBonus b where b.clientId = :c and b.sessionTypeId = :t and b.cancelledAt is null "
         + "and b.expiresAt >= :day and b.used < b.total order by b.expiresAt asc, b.id asc")
    List<ClientBonus> lockUsable(@Param("c") Long clientId, @Param("t") Long sessionTypeId, @Param("day") LocalDate day);
}
