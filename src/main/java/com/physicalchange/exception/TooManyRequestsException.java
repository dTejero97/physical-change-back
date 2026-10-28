package com.physicalchange.exception;

/** 429 con Retry-After: límite de peticiones o de intentos de inicio de sesión (T141). */
public class TooManyRequestsException extends RuntimeException {
    private final long retryAfterSeconds;
    public TooManyRequestsException(String message, long retryAfterSeconds) { super(message); this.retryAfterSeconds = retryAfterSeconds; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
