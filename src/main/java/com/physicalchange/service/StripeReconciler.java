package com.physicalchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.physicalchange.domain.PurchaseOrder;
import com.physicalchange.payment.PaymentException;
import com.physicalchange.payment.PaymentProvider;
import com.physicalchange.repository.PurchaseOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conciliación de pagos con Stripe cuando no llega el webhook (T245, docs/api-pagos-stripe.md "Conciliación sin webhook").
 * Lee la sesión de Checkout desde el servidor (nunca se fía del navegador) FUERA de cualquier transacción y la aplica con
 * {@link StripeWebhookService#reconcile}, que bloquea el pedido. Como mucho una consulta a Stripe por pedido cada 10 s
 * (en memoria, por instancia). Un fallo de Stripe nunca se propaga: se deja el pedido como está y se reintenta después.
 */
@Service
public class StripeReconciler {
    private static final Logger log = LoggerFactory.getLogger(StripeReconciler.class);
    static final Duration MIN_GAP = Duration.ofSeconds(10);
    static final Duration JOB_MIN_AGE = Duration.ofMinutes(2);
    static final Duration JOB_MAX_AGE = Duration.ofHours(48);

    private final PaymentProvider payments;
    private final PurchaseOrderRepository orders;
    private final StripeWebhookService webhook;
    private final Clock clock;
    private final Map<Long, Instant> lastCheck = new ConcurrentHashMap<>();

    public StripeReconciler(PaymentProvider p, PurchaseOrderRepository o, StripeWebhookService w, Clock c) {
        payments = p; orders = o; webhook = w; clock = c;
    }

    /** Concilia el pedido si sigue PENDING, tiene sesión y no se consultó en los últimos 10 s. true si cambió. */
    public boolean reconcileIfDue(PurchaseOrder o) {
        if (!payments.enabled() || o == null || !"PENDING".equals(o.getStatus()) || o.getStripeSessionId() == null) return false;
        Instant now = clock.instant();
        Instant prev = lastCheck.get(o.getId());
        if (prev != null && Duration.between(prev, now).compareTo(MIN_GAP) < 0) return false;
        // putIfAbsent/replace: si dos peticiones llegan a la vez, solo una consulta a Stripe.
        boolean mine = prev == null ? lastCheck.putIfAbsent(o.getId(), now) == null : lastCheck.replace(o.getId(), prev, now);
        if (!mine) return false;
        forgetOld(now);
        JsonNode session;
        try {
            session = payments.retrieveCheckout(o.getStripeSessionId());
        } catch (PaymentException e) {
            log.warn("Stripe: no se ha podido conciliar el pedido {} ({}); se reintentará", o.getId(), e.getMessage());
            return false;
        }
        boolean changed = webhook.reconcile(o.getId(), session);
        if (changed) lastCheck.remove(o.getId());
        return changed;
    }

    /** Pedidos pendientes de hace entre 2 min y 48 h, por si el cliente no vuelve a la app tras pagar. */
    @Scheduled(fixedDelayString = "${app.payments.reconcile-interval-ms:300000}",
               initialDelayString = "${app.payments.reconcile-initial-delay-ms:120000}")
    public void reconcilePending() {
        if (!payments.enabled()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        for (PurchaseOrder o : orders.findTop20ByStatusAndStripeSessionIdIsNotNullAndCreatedAtBetweenOrderByCreatedAtAsc(
                "PENDING", now.minus(JOB_MAX_AGE), now.minus(JOB_MIN_AGE))) {
            try {
                reconcileIfDue(o);
            } catch (RuntimeException e) {
                log.warn("Stripe: error al conciliar el pedido {}: {}", o.getId(), e.toString());
            }
        }
    }

    private void forgetOld(Instant now) {
        if (lastCheck.size() > 1000) lastCheck.values().removeIf(t -> Duration.between(t, now).toHours() >= 1);
    }
}
