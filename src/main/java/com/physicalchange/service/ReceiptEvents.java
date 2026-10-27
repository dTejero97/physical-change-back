package com.physicalchange.service;

/**
 * Eventos de comprobantes (ADR-004 §5). Se publican dentro de la transacción de ReceiptService;
 * los bonos (T68) los escuchan para conceder sesiones al pagar y cancelar las no usadas al anular.
 */
public final class ReceiptEvents {
    private ReceiptEvents() {}

    /** Un comprobante ha pasado a PAID (al crearlo pagado o al marcarlo como pagado). */
    public record ReceiptPaidEvent(Long receiptId) {}

    /** Un comprobante se ha anulado. wasPaid = estaba cobrado (cuenta como anulación/reembolso). */
    public record ReceiptVoidedEvent(Long receiptId, boolean wasPaid) {}
}
