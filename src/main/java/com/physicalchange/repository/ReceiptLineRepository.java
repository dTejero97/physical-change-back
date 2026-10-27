package com.physicalchange.repository;

import com.physicalchange.domain.ReceiptLine;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ReceiptLineRepository extends JpaRepository<ReceiptLine, Long> {
    List<ReceiptLine> findByReceiptIdInOrderByReceiptIdAscPositionAsc(Collection<Long> receiptIds);
    List<ReceiptLine> findByReceiptIdOrderByPositionAsc(Long receiptId);

    /** Unidades vendidas por producto (comprobantes no anulados). */
    @Query("select l.productId, sum(l.quantity) from ReceiptLine l, Receipt r where r.id = l.receiptId and r.status <> 'VOID' "
         + "and l.productId in :ids group by l.productId")
    List<Object[]> salesByProduct(@Param("ids") Collection<Long> productIds);

    @Query("select l.planId, sum(l.quantity) from ReceiptLine l, Receipt r where r.id = l.receiptId and r.status <> 'VOID' "
         + "and l.planId in :ids group by l.planId")
    List<Object[]> salesByPlan(@Param("ids") Collection<Long> planIds);

    /** Cualquier línea (también anulada) impide borrar el producto: se archiva (clave ajena). */
    boolean existsByProductId(Long productId);
    boolean existsByPlanId(Long planId);
}
