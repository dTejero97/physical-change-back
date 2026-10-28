package com.physicalchange.security;

import com.physicalchange.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Intentos fallidos de inicio de sesión por usuario (T141): tras N fallos seguidos la cuenta queda bloqueada
 * unos minutos aunque el atacante cambie de IP. Un inicio de sesión correcto pone el contador a cero.
 */
@Component
public class LoginAttempts {
    private final RateLimiter limiter = new RateLimiter();
    private final int maxFailures;
    private final long windowMs;

    public LoginAttempts(@Value("${app.rate-limit.login-failures-per-user:5}") int maxFailures,
                         @Value("${app.rate-limit.login-lock-minutes:15}") int lockMinutes) {
        this.maxFailures = maxFailures;
        this.windowMs = lockMinutes * 60_000L;
    }

    public void check(String username) {
        long wait = limiter.blockedFor(username, maxFailures, windowMs);
        if (wait > 0) throw new TooManyRequestsException("Demasiados intentos fallidos. Vuelve a intentarlo en unos minutos.", wait);
    }

    public void failed(String username) { limiter.hit(username, maxFailures, windowMs); }
    public void succeeded(String username) { limiter.reset(username); }
}
