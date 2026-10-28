package com.physicalchange.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Límites por IP y tamaño de los parámetros de consulta (Nota de Seguridad T140, T141):
 *  - POST /api/auth/login: pocos intentos por minuto y por IP (además del bloqueo por usuario de LoginAttempts).
 *  - /api/public/**: rutas sin autenticación (invitaciones y archivos firmados).
 *  - resto de /api/**: límite general contra el abuso.
 *  - parámetros de consulta: 400 si alguno es desproporcionado (búsquedas con textos enormes).
 * La IP es la última entrada de X-Forwarded-For (la que añade el proxy de Render); la primera la puede falsear el cliente.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long MINUTE = 60_000;
    static final int MAX_PARAM_LENGTH = 500;

    private final RateLimiter limiter = new RateLimiter();
    private final boolean enabled, trustProxy;
    private final int loginPerIp, publicPerIp, apiPerIp;

    public RateLimitFilter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.rate-limit.trust-proxy:true}") boolean trustProxy,
                           @Value("${app.rate-limit.login-per-ip-per-minute:10}") int loginPerIp,
                           @Value("${app.rate-limit.public-per-ip-per-minute:300}") int publicPerIp,
                           @Value("${app.rate-limit.api-per-ip-per-minute:600}") int apiPerIp) {
        this.enabled = enabled; this.trustProxy = trustProxy;
        this.loginPerIp = loginPerIp; this.publicPerIp = publicPerIp; this.apiPerIp = apiPerIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }

        for (Map.Entry<String, String[]> p : req.getParameterMap().entrySet()) {
            for (String v : p.getValue()) {
                if (v != null && v.length() > MAX_PARAM_LENGTH) { error(res, 400, "Hay un parámetro demasiado largo", 0); return; }
            }
        }

        if (enabled) {
            String ip = clientIp(req);
            long wait;
            if ("POST".equals(req.getMethod()) && path.equals("/api/auth/login")) wait = limiter.hit("login:" + ip, loginPerIp, MINUTE);
            else if (path.startsWith("/api/public/")) wait = limiter.hit("public:" + ip, publicPerIp, MINUTE);
            else wait = limiter.hit("api:" + ip, apiPerIp, MINUTE);
            if (wait > 0) { error(res, 429, "Demasiadas peticiones. Espera un momento y vuelve a intentarlo.", wait); return; }
        }
        chain.doFilter(req, res);
    }

    String clientIp(HttpServletRequest req) {
        String xff = trustProxy ? req.getHeader("X-Forwarded-For") : null;
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }

    private static void error(HttpServletResponse res, int status, String message, long retryAfter) throws IOException {
        res.setStatus(status);
        if (retryAfter > 0) res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/json;charset=UTF-8");
        String reason = status == 429 ? "Too Many Requests" : "Bad Request";
        res.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status + ",\"error\":\"" + reason
                + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
