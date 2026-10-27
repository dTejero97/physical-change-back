package com.physicalchange.repository;

import com.physicalchange.domain.Receipt;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    /** Comprobantes de un profesional emitidos en el rango (los filtros finos se aplican en ReceiptService). */
    List<Receipt> findByTrainerIdAndIssueDateBetween(Long trainerId, LocalDate from, LocalDate to);
    /** Fachada /api/payments: sin anulados (antes se borraban). */
    Page<Receipt> findByTrainerIdAndStatusNotOrderByDueDateDescIdDesc(Long trainerId, String status, Pageable p);
    Page<Receipt> findByClientIdAndStatusNotOrderByDueDateDescIdDesc(Long clientId, String status, Pageable p);
    /** Para el "último pago" de la ficha del cliente (sin anulados). */
    List<Receipt> findByClientIdInAndStatusNotOrderByDueDateDesc(Collection<Long> clientIds, String status);
    /** Movimientos (ADMIN): ingresos cobrados por fecha de pago. */
    List<Receipt> findByTrainerIdAndStatusAndPaidDateBetween(Long trainerId, String status, LocalDate from, LocalDate to);

    @Query("select coalesce(sum(r.total), 0) from Receipt r where r.trainerId = :t and r.status = :s")
    BigDecimal sumByTrainerAndStatus(@Param("t") Long trainerId, @Param("s") String status);

    @Query("select count(r) from Receipt r where r.trainerId = :t and r.status = 'PENDING' and r.dueDate < :today")
    long countOverdue(@Param("t") Long trainerId, @Param("today") LocalDate today);

    /** Puesta al día idempotente: un comprobante por periodo (índice único ux_receipt_period). */
    boolean existsBySubscriptionIdAndPeriodStart(Long subscriptionId, LocalDate periodStart);

    /** Datos de negocio: cobrados en el rango (cualquier estado: los VOID con was_paid conservan paidDate). */
    List<Receipt> findByTrainerIdAndPaidDateBetween(Long trainerId, LocalDate from, LocalDate to);
    /** Anulaciones de comprobantes cobrados en el rango. */
    List<Receipt> findByTrainerIdAndWasPaidTrueAndVoidedAtBetween(Long trainerId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
