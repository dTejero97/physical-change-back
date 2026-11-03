package com.physicalchange.repository;

import com.physicalchange.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    Optional<PurchaseOrder> findByStripeSessionId(String sessionId);
    Optional<PurchaseOrder> findFirstByStripePaymentIntent(String paymentIntent);
    List<PurchaseOrder> findByClientIdOrderByCreatedAtDesc(Long clientId);
    List<PurchaseOrder> findByReceiptIdAndStatus(Long receiptId, String status);
    List<PurchaseOrder> findByClientIdAndPlanIdAndStatus(Long clientId, Long planId, String status);
}
