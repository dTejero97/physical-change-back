package com.physicalchange.payment;

/** Pagos no activados (503) o Stripe no disponible / ha rechazado la petición (502). El mensaje es apto para el usuario. */
public class PaymentException extends RuntimeException {
    private final int status;

    public PaymentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }

    public static PaymentException disabled() { return new PaymentException(503, "Los pagos no están activados"); }
}
