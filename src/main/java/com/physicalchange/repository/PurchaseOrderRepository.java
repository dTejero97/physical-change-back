package com.physicalchange.repository;

import com.physicalchange.domain.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    Optional<PurchaseOrder> findByStripeSessionId(String sessionId);
    Optional<PurchaseOrder> findFirstByStripePaymentIntent(String paymentIntent);
    List<PurchaseOrder> findByClientIdOrderByCreatedAtDesc(Long clientId);
    List<PurchaseOrder> findByReceiptIdAndStatus(Long receiptId, String status);
    List<PurchaseOrder> findByClientIdAndPlanIdAndStatus(Long clientId, Long planId, String status);

    /** Webhook y conciliación bloquean la fila del pedido antes de aplicar nada (T245): el segundo ve el estado final. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.stripeSessionId = :sessionId")
    Optional<PurchaseOrder> lockByStripeSessionId(@Param("sessionId") String sessionId);

    /** Pedidos de una cuenta conectada en un estado (ADR-018: caducar sus sesiones al desvincularla). */
    List<PurchaseOrder> findByStripeAccountIdAndStatus(String stripeAccountId, String status);

    /** Pedidos que la tarea programada concilia con Stripe (T245): pendientes, con sesión, dentro de la ventana. */
    List<PurchaseOrder> findTop20ByStatusAndStripeSessionIdIsNotNullAndCreatedAtBetweenOrderByCreatedAtAsc(
            String status, LocalDateTime from, LocalDateTime to);
}
