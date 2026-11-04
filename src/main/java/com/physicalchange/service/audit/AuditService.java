package com.physicalchange.service.audit;

import com.physicalchange.domain.AuditEvent;
import com.physicalchange.repository.AuditEventRepository;
import com.physicalchange.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;

/**
 * Registro de auditoría (ADR-016). {@link #record} escribe cuando la transacción en curso se confirma (lo que se deshace no
 * se audita) y {@link #recordNow} al momento (para lo que termina en error, como un login fallido). Siempre en una
 * transacción propia: si falla, se avisa en el log y la operación del usuario sigue.
 * El resumen nunca debe llevar contraseñas, tokens, emails ni datos de salud (ADR-016 §4).
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditEventRepository events;
    private final TransactionTemplate tx;
    private final boolean trustProxy;

    public AuditService(AuditEventRepository e, PlatformTransactionManager tm, @Value("${app.rate-limit.trust-proxy:true}") boolean trustProxy) {
        events = e;
        tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.trustProxy = trustProxy;
    }

    /** Acción del usuario en sesión, tras el commit. */
    public void record(AuditAction action, String entityType, Long entityId, Long clientId, String summary) {
        AuthUser me = currentUser();
        recordAs(me == null ? null : me.id(), me == null ? null : me.role().name(), action, entityType, entityId, clientId, summary);
    }

    /** Acción con el actor indicado (p. ej. el usuario que acaba de iniciar sesión, o null = el sistema), tras el commit. */
    public void recordAs(Long actorId, String actorRole, AuditAction action, String entityType, Long entityId, Long clientId, String summary) {
        AuditEvent e = build(actorId, actorRole, action, entityType, entityId, clientId, summary);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write(e); }
            });
        } else {
            write(e);
        }
    }

    /** Al momento, aunque la transacción en curso se deshaga (login fallido, bloqueo). */
    public void recordNow(Long actorId, String actorRole, AuditAction action, String entityType, Long entityId, Long clientId, String summary) {
        write(build(actorId, actorRole, action, entityType, entityId, clientId, summary));
    }

    private void write(AuditEvent e) {
        try {
            tx.executeWithoutResult(s -> events.save(e));
        } catch (RuntimeException ex) {
            log.warn("Auditoría: no se ha podido guardar {} ({})", e.getAction(), ex.getClass().getSimpleName());
        }
    }

    private AuditEvent build(Long actorId, String actorRole, AuditAction action, String entityType, Long entityId, Long clientId, String summary) {
        HttpServletRequest req = request();
        return new AuditEvent(actorId, actorRole, action.name(), entityType, entityId, clientId, cut(summary),
                req == null ? null : truncateIp(clientIp(req)), req == null ? null : requestId(req));
    }

    private static AuthUser currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser u ? u : null;
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    /** Misma regla que RateLimitFilter: la última entrada de X-Forwarded-For es la que pone el proxy de Render. */
    private String clientIp(HttpServletRequest req) {
        String xff = trustProxy ? req.getHeader("X-Forwarded-For") : null;
        if (xff != null && !xff.isBlank()) { String[] p = xff.split(","); return p[p.length - 1].trim(); }
        return req.getRemoteAddr();
    }

    private static String requestId(HttpServletRequest req) {
        Object id = req.getAttribute("audit.requestId");
        if (id == null) { id = UUID.randomUUID().toString(); req.setAttribute("audit.requestId", id); }
        return id.toString();
    }

    /** IPv4 a /24 (último octeto a 0) e IPv6 a /48 (tres primeros grupos): suficiente para investigar, menos dato personal. */
    static String truncateIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (ip.contains(":")) {
            String[] g = ip.split(":");
            return g.length >= 3 ? g[0] + ":" + g[1] + ":" + g[2] + "::" : ip;
        }
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) + ".0" : ip;
    }

    /** Usuario tecleado en un login fallido, enmascarado: "ana@x.com" → "a***@x.com"; "chani" → "c***". */
    public static String maskLogin(String login) {
        if (login == null || login.isBlank()) return "(vacío)";
        String s = login.strip();
        int at = s.indexOf('@');
        String local = at > 0 ? s.substring(0, at) : s, rest = at > 0 ? s.substring(at) : "";
        return local.charAt(0) + "***" + (rest.length() > 60 ? rest.substring(0, 60) : rest);
    }

    private static String cut(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= 300 ? t : t.substring(0, 299) + "…";
    }
}
