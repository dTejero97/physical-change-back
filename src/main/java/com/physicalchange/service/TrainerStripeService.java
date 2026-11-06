package com.physicalchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.ConflictException;
import com.physicalchange.exception.NotFoundException;
import com.physicalchange.exception.TooManyRequestsException;
import com.physicalchange.security.RateLimiter;
import com.physicalchange.payment.PaymentException;
import com.physicalchange.payment.PaymentProvider;
import com.physicalchange.repository.*;
import com.physicalchange.service.audit.AuditAction;
import com.physicalchange.service.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Cuenta Stripe Connect de cada profesional (ADR-018, docs/api-stripe-profesional.md): vincular (Standard por OAuth o
 * Express por enlace de alta), ver su estado, actualizarlo, desvincularla y aplicar los eventos de cuenta del webhook de
 * Connect. Nunca se guardan claves: solo el id de cuenta, y el id nunca sale en las respuestas ni en los logs.
 * La vuelta desde Stripe es pública: el state de un solo uso identifica al profesional (no hay sesión en esa petición).
 */
@Service
public class TrainerStripeService {
    private static final Logger log = LoggerFactory.getLogger(TrainerStripeService.class);
    static final Duration STATE_TTL = Duration.ofMinutes(10);
    static final String STANDARD = "STANDARD", EXPRESS = "EXPRESS";
    static final List<String> OPEN_SUBSCRIPTION_STATUSES = List.of("ACTIVE", "PAST_DUE");
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int REFRESHES_PER_HOUR = 10;
    private final RateLimiter refreshLimiter = new RateLimiter();

    private final PaymentProvider payments;
    private final TrainerStripeAccountRepository accounts;
    private final StripeOauthStateRepository states;
    private final ClientSubscriptionRepository subscriptions;
    private final PurchaseOrderRepository orders;
    private final UserRepository users;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final String publicUrl, publicApiUrl;

    public TrainerStripeService(PaymentProvider p, TrainerStripeAccountRepository a, StripeOauthStateRepository s,
                                ClientSubscriptionRepository cs, PurchaseOrderRepository o, UserRepository u, AuditService au,
                                ObjectMapper j, Clock c, @Value("${app.public-url}") String publicUrl,
                                @Value("${app.public-api-url:http://localhost:8080}") String publicApiUrl) {
        payments = p; accounts = a; states = s; subscriptions = cs; orders = o; users = u; audit = au; json = j; clock = c;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.publicApiUrl = publicApiUrl.replaceAll("/+$", "");
    }

    /** Estado de la cuenta del profesional. Sin vincular → NOT_LINKED (200, no 404). */
    @Transactional(readOnly = true)
    public StripeAccountDto status(Long trainerId) {
        return accounts.findById(trainerId).map(TrainerStripeService::dto).orElse(notLinked());
    }

    /** Empieza la vinculación: devuelve la URL a la que debe ir el profesional (Stripe). */
    @Transactional
    public StripeLinkDto link(Long trainerId, String requestedType) {
        if (!payments.enabled()) throw PaymentException.disabled();
        String type = requestedType == null || requestedType.isBlank() ? STANDARD : requestedType.trim().toUpperCase();
        if (!STANDARD.equals(type) && !EXPRESS.equals(type)) throw new ConflictException("Tipo de cuenta no válido (STANDARD o EXPRESS)");
        if (accounts.existsById(trainerId)) throw new ConflictException("Ya tienes una cuenta de Stripe vinculada. Desvincúlala antes");
        if (STANDARD.equals(type) && !payments.connectConfigured()) throw PaymentException.connectNotConfigured();
        String accountId = null;
        if (EXPRESS.equals(type)) {
            AppUser me = users.findById(trainerId).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
            accountId = payments.createExpressAccount(me.getEmail(), "ES");
        }
        String state = newState();
        states.save(new StripeOauthState(state, trainerId, type, accountId, LocalDateTime.now(clock).plus(STATE_TTL)));
        String url = EXPRESS.equals(type)
                ? payments.accountLinkUrl(accountId, publicUrl + "/negocio/pagos-online?stripe=reintentar", callbackUrl() + "?state=" + state)
                : payments.connectAuthorizeUrl(state, callbackUrl());
        return new StripeLinkDto(url);
    }

    /**
     * Vuelta desde Stripe (pública). Siempre responde con la URL a la que redirigir: nunca JSON ni error HTTP al navegador.
     * El state se usa una sola vez, también cuando la vinculación falla (así no se puede reintentar con el mismo).
     */
    @Transactional
    public String callback(String code, String state, String error) {
        if (state == null || state.isBlank()) return failure("state");
        Optional<StripeOauthState> found = states.lockByState(state);
        if (found.isEmpty() || found.get().getUsedAt() != null || found.get().getExpiresAt().isBefore(LocalDateTime.now(clock)))
            return failure("state");
        StripeOauthState s = found.get();
        s.setUsedAt(LocalDateTime.now(clock));
        if (error != null) return failure("cancelado");
        String accountId = s.getStripeAccountId();
        if (STANDARD.equals(s.getAccountType())) {
            if (code == null || code.isBlank()) return failure("oauth");
            try {
                accountId = payments.exchangeOAuthCode(code);
            } catch (PaymentException e) {
                return failure("oauth");
            }
        }
        if (accountId == null) return failure("oauth");
        Long trainerId = s.getTrainerId();
        if (accounts.findByStripeAccountId(accountId).filter(a -> !a.getTrainerId().equals(trainerId)).isPresent())
            return failure("cuenta_en_uso");
        JsonNode acc;
        try {
            acc = payments.retrieveAccount(accountId);
        } catch (PaymentException e) {
            return failure("oauth");
        }
        TrainerStripeAccount row = accounts.findById(trainerId).orElseGet(() -> {
            TrainerStripeAccount n = new TrainerStripeAccount();
            n.setTrainerId(trainerId);
            n.setLinkedAt(LocalDateTime.now(clock));
            return n;
        });
        row.setStripeAccountId(accountId);
        row.setAccountType(s.getAccountType());
        applyAccount(row, acc);
        accounts.save(row);
        audit.recordAs(trainerId, "TRAINER", AuditAction.STRIPE_ACCOUNT_LINKED, "TRAINER", trainerId, null,
                s.getAccountType() + " · " + last4(accountId));
        return publicUrl + "/negocio/pagos-online?stripe=ok";
    }

    /** Vuelve a leer la cuenta en Stripe (tras completar datos o al volver del alta). Límite: 10 por hora (contrato). */
    @Transactional
    public StripeAccountDto refresh(Long trainerId) {
        long wait = refreshLimiter.hit("stripe-refresh:" + trainerId, REFRESHES_PER_HOUR, 60 * 60_000L);
        if (wait > 0) throw new TooManyRequestsException("Demasiadas comprobaciones. Inténtalo dentro de un rato", wait);
        Optional<TrainerStripeAccount> row = accounts.findById(trainerId);
        if (row.isEmpty()) return notLinked();
        applyAccount(row.get(), payments.retrieveAccount(row.get().getStripeAccountId()));
        return dto(row.get());
    }

    /**
     * Desvincula la cuenta. Con suscripciones activas o con impago en esa cuenta se rechaza (ADR-018, punto 8): primero hay
     * que cancelarlas. Las sesiones de Checkout abiertas en esa cuenta se caducan para que nadie pague después.
     */
    @Transactional
    public void unlink(Long trainerId) {
        Optional<TrainerStripeAccount> found = accounts.findById(trainerId);
        if (found.isEmpty()) return;
        TrainerStripeAccount row = found.get();
        String accountId = row.getStripeAccountId();
        if (subscriptions.existsByStripeAccountIdAndStatusIn(accountId, OPEN_SUBSCRIPTION_STATUSES))
            throw new ConflictException("Tienes suscripciones activas cobradas en tu cuenta de Stripe. Cancélalas antes de desvincularla");
        // Express no se puede revocar por OAuth: la cuenta queda en Stripe, pero deja de estar vinculada aquí.
        if (STANDARD.equals(row.getAccountType())) payments.deauthorize(accountId);
        for (PurchaseOrder o : orders.findByStripeAccountIdAndStatus(accountId, "PENDING")) {
            if (o.getStripeSessionId() != null) payments.expireCheckout(o.getStripeSessionId(), accountId);
            o.setStatus("EXPIRED");
        }
        accounts.delete(row);
        audit.recordAs(trainerId, "TRAINER", AuditAction.STRIPE_ACCOUNT_UNLINKED, "TRAINER", trainerId, null,
                row.getAccountType() + " · " + last4(accountId));
    }

    /** Cuenta del profesional lista para cobrar (charges_enabled). Sin ella, sus planes y comprobantes no se pagan. */
    @Transactional(readOnly = true)
    public Optional<TrainerStripeAccount> chargeable(Long trainerId) {
        return accounts.findById(trainerId).filter(a -> "ACTIVE".equals(a.getStatus()) && a.isChargesEnabled());
    }

    // ---------- Webhook de cuentas conectadas (ADR-018, punto 6) ----------

    /** account.updated: actualiza el estado de la cuenta si la tenemos vinculada. Las cuentas desconocidas se ignoran. */
    @Transactional
    public void accountUpdated(JsonNode account) {
        String id = account.path("id").asText("");
        accounts.findByStripeAccountId(id).ifPresent(row -> applyAccount(row, account));
    }

    /**
     * account.application.deauthorized: el profesional ha revocado la conexión desde Stripe. Se desvincula y las
     * suscripciones activas pasan a impago para que se revisen (no se cancelan en silencio).
     */
    @Transactional
    public void accountDeauthorized(String accountId) {
        accounts.findByStripeAccountId(accountId).ifPresent(row -> {
            for (ClientSubscription s : subscriptions.findByStripeAccountIdAndStatusIn(accountId, List.of("ACTIVE"))) {
                s.setStatus("PAST_DUE");
            }
            log.warn("Stripe: el profesional {} ha revocado la cuenta {}; sus suscripciones activas pasan a impago",
                    row.getTrainerId(), last4(accountId));
            accounts.delete(row);
            audit.recordAs(row.getTrainerId(), "TRAINER", AuditAction.STRIPE_ACCOUNT_UNLINKED, "TRAINER", row.getTrainerId(), null,
                    "revocada en Stripe · " + last4(accountId));
        });
    }

    // ---------- internos ----------

    /** Estado derivado de la cuenta (docs/api-stripe-profesional.md, "Estados"). */
    static String statusOf(JsonNode acc) {
        if (acc.path("charges_enabled").asBoolean(false)) return "ACTIVE";
        boolean due = !acc.path("requirements").path("currently_due").isEmpty();
        if (!acc.path("details_submitted").asBoolean(false) || due) return "PENDING_DETAILS";
        return "RESTRICTED";
    }

    private void applyAccount(TrainerStripeAccount row, JsonNode acc) {
        row.setStatus(statusOf(acc));
        row.setChargesEnabled(acc.path("charges_enabled").asBoolean(false));
        row.setPayoutsEnabled(acc.path("payouts_enabled").asBoolean(false));
        row.setDetailsSubmitted(acc.path("details_submitted").asBoolean(false));
        row.setRequirementsDue(requirements(acc));
        row.setUpdatedAt(LocalDateTime.now(clock));
        accounts.save(row);
    }

    /** Requisitos pendientes como JSON (columna de 2000 caracteres; si no cabe, se guarda vacío). */
    private String requirements(JsonNode acc) {
        String s = acc.path("requirements").path("currently_due").toString();
        return s.length() > 2000 ? "[]" : s;
    }

    private String callbackUrl() { return publicApiUrl + "/api/public/stripe/connect/callback"; }

    private String failure(String motivo) { return publicUrl + "/negocio/pagos-online?stripe=error&motivo=" + motivo; }

    private static String newState() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Solo los últimos 4 caracteres del id de cuenta (ADR-016 y contrato). */
    static String last4(String accountId) {
        return accountId == null ? "" : "···" + accountId.substring(Math.max(0, accountId.length() - 4));
    }

    static StripeAccountDto notLinked() {
        return new StripeAccountDto(null, "NOT_LINKED", false, false, false, List.of(), null);
    }

    static StripeAccountDto dto(TrainerStripeAccount a) {
        return new StripeAccountDto(a.getAccountType(), a.getStatus(), a.isChargesEnabled(), a.isPayoutsEnabled(),
                a.isDetailsSubmitted(), requirementList(a.getRequirementsDue()), a.getLinkedAt());
    }

    private static List<String> requirementList(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw)) return List.of();
        return List.of(raw.replace("[", "").replace("]", "").replace("\"", "").split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
