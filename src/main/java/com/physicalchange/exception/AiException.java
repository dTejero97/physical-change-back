package com.physicalchange.exception;

/**
 * Errores de Physical AI con su código (docs/api-ia.md): 402 sin créditos, 422 salida inválida, 429 límite (con Retry-After)
 * y 503 no configurado, presupuesto agotado o proveedor caído. Nunca se cobra cuando se lanza.
 */
public class AiException extends RuntimeException {
    private final int status;
    private final Integer retryAfterSeconds;

    public AiException(int status, String message) { this(status, message, null); }
    public AiException(int status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
