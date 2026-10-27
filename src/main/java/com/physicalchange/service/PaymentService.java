package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Fachada de compatibilidad /api/payments (ADR-004 §2): mismo PaymentDto, pero lee y escribe comprobantes.
 * El id es el del comprobante. Un cobro es un comprobante con una línea libre; los anulados no se listan (antes se borraban).
 * El cliente solo consulta los suyos.
 */
@Service
public class PaymentService {
    private final ReceiptRepository receipts;
    private final ReceiptService receiptService;
    private final AccessService access;

    public PaymentService(ReceiptRepository r, ReceiptService rs, AccessService a) { receipts = r; receiptService = rs; access = a; }

    @Transactional(readOnly = true)
    public Page<PaymentDto> list(Pageable p) {
        AuthUser me = access.current();
        Pageable unsorted = PageRequest.of(p.getPageNumber(), p.getPageSize());
        Page<Receipt> page = me.role() == Role.CLIENT
                ? receipts.findByClientIdAndStatusNotOrderByDueDateDescIdDesc(me.id(), "VOID", unsorted)
                : receipts.findByTrainerIdAndStatusNotOrderByDueDateDescIdDesc(me.id(), "VOID", unsorted);
        return receiptService.page(page, (x, d) -> toPayment(d, x.getTrainerId()));
    }

    @Transactional
    public PaymentDto create(PaymentRequest r) {
        LocalDate today = LocalDate.now();
        String concept = r.concept().strip();
        ReceiptDto d = receiptService.create(new ReceiptRequest(r.clientId(), r.dueDate().isBefore(today) ? r.dueDate() : today, r.dueDate(),
                concept.length() > 120 ? concept : null, "OTHER", "PENDING", null,
                List.of(new ReceiptLineRequest("FREE", null, concept, r.amount(), 1, 0)), null, null, null));
        return toPayment(d, access.current().id());
    }

    @Transactional
    public PaymentDto markPaid(Long id) {
        if ("PAID".equals(receiptService.owned(id).getStatus())) throw new ConflictException("El pago ya está marcado como pagado");
        return toPayment(receiptService.pay(id, null), access.current().id());
    }

    /** "Borrar" un cobro pendiente = anularlo. Uno cobrado sigue sin poder borrarse. */
    @Transactional
    public void delete(Long id) {
        Receipt x = receiptService.owned(id);
        if ("PAID".equals(x.getStatus())) throw new ConflictException("Un pago cobrado no se puede borrar");
        if ("VOID".equals(x.getStatus())) throw new NotFoundException("Pago no encontrado");
        receiptService.voidReceipt(id, "Borrado desde Pagos");
    }

    /** Concepto = primera línea (o las notas si el concepto original no cabía en la línea). */
    private static PaymentDto toPayment(ReceiptDto d, Long trainerId) {
        String concept = d.notes() != null && d.lines().size() == 1 && d.notes().startsWith(d.lines().get(0).name()) ? d.notes()
                : d.lines().isEmpty() ? d.reference() : d.lines().get(0).name() + (d.lines().size() > 1 ? " (+" + (d.lines().size() - 1) + ")" : "");
        BigDecimal amount = d.total();
        return new PaymentDto(d.id(), trainerId, d.clientId(), d.clientName(), amount, concept, d.dueDate(), d.paidDate(), d.status(), d.overdue());
    }
}
