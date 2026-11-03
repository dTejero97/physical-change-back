package com.physicalchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.physicalchange.domain.*;
import com.physicalchange.payment.PaymentProvider;
import com.physicalchange.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Webhook de Stripe (ADR-009 §4–6): la ÚNICA vía por la que un pago online se da por cobrado. La firma se verifica antes
 * de leer el cuerpo (PaymentProvider.verifyWebhook). Cada evento se aplica una vez (tabla stripe_event): si Stripe lo
 * reintenta, se responde 200 sin repetir el efecto. Un error inesperado responde 500 y Stripe lo reintentará.
 *
 * Campos usados (estables entre versiones de la API; invoice.subscription se lee también de
 * invoice.parent.subscription_details.subscription, donde lo mueven las versiones de 2025):
 * checkout.session: id, mode, payment_status, amount_total, currency, payment_intent, subscription ·
 * invoice: billing_reason, subscription · subscription: id, status, cancel_at_period_end, cancel_at ·
 * charge: payment_intent, refunded.
 */
@Service
public class StripeWebhookService {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final PaymentProvider payments;
    private final StripeEventRepository events;
    private final PurchaseOrderRepository orders;
    private final SubscriptionPlanRepository plans;
    private final ClientSubscriptionRepository subscriptions;
    private final ReceiptService receipts;
    private final SubscriptionService subscriptionService;

    public StripeWebhookService(PaymentProvider pay, StripeEventRepository e, PurchaseOrderRepository o, SubscriptionPlanRepository p,
                                ClientSubscriptionRepository s, ReceiptService r, SubscriptionService ss) {
        payments = pay; events = e; orders = o; plans = p; subscriptions = s; receipts = r; subscriptionService = ss;
    }

    /** Verifica la firma y devuelve el evento (InvalidSignatureException → 400). Se llama antes de apply, fuera de la transacción. */
    public JsonNode verify(byte[] payload, String signature) { return payments.verifyWebhook(payload, signature); }

    /** Aplica un evento ya verificado, todo o nada (si falla, no queda registrado y Stripe lo reintenta). */
    @Transactional
    public void apply(JsonNode event) {
        String id = event.path("id").asText(""), type = event.path("type").asText("");
        if (id.isEmpty() || events.existsById(id)) return;
        // T130: un evento de pruebas nunca cobra en real ni al revés (defensa extra además de la firma por endpoint).
        if (event.has("livemode") && event.path("livemode").asBoolean() != "LIVE".equals(payments.mode())) {
            log.warn("Stripe: evento {} ignorado: su modo (livemode={}) no coincide con la clave ({})", id, event.path("livemode").asBoolean(), payments.mode());
            return;
        }
        events.save(new StripeEvent(id, truncate(type, 80)));
        JsonNode obj = event.path("data").path("object");
        log.info("Stripe: evento {} ({})", type, id);
        switch (type) {
            case "checkout.session.completed" -> { if ("paid".equals(obj.path("payment_status").asText())) fulfil(obj); }
            case "checkout.session.async_payment_succeeded" -> fulfil(obj);
            case "checkout.session.async_payment_failed" -> closeOrder(obj, "FAILED");
            case "checkout.session.expired" -> closeOrder(obj, "EXPIRED");
            case "invoice.paid" -> invoicePaid(obj);
            case "invoice.payment_failed" -> invoiceFailed(obj);
            case "customer.subscription.updated" -> subscriptionUpdated(obj);
            case "customer.subscription.deleted" -> subscriptionDeleted(obj);
            case "charge.refunded" -> refunded(obj);
            default -> { /* evento no suscrito o sin efecto: queda registrado */ }
        }
    }

    // ---------- Checkout ----------

    private void fulfil(JsonNode session) {
        PurchaseOrder o = order(session).orElse(null);
        if (o == null || "PAID".equals(o.getStatus()) || "REFUNDED".equals(o.getStatus())) return;
        // El importe lo fijamos nosotros al crear la sesión; si no coincide, no se concede nada.
        long paid = session.path("amount_total").asLong(-1);
        if (paid != OnlinePaymentService.cents(o.getAmount()) || !"eur".equalsIgnoreCase(session.path("currency").asText())) {
            log.error("Stripe: el pedido {} no coincide con lo cobrado ({} {}); no se aplica", o.getId(), paid, session.path("currency").asText());
            o.setStatus("FAILED");
            return;
        }
        o.setStatus("PAID");
        o.setPaidAt(LocalDateTime.now());
        o.setStripePaymentIntent(text(session.path("payment_intent")));
        if ("RECEIPT".equals(o.getKind())) {
            if (!receipts.markPaidByStripe(o.getReceiptId()))
                log.warn("Stripe: el comprobante {} ya no estaba pendiente; el pedido {} está cobrado y hay que reembolsarlo en Stripe",
                        o.getReceiptId(), o.getId());
        } else {
            startSubscription(o, text(session.path("subscription")));
        }
    }

    private void startSubscription(PurchaseOrder o, String stripeSubscriptionId) {
        if (stripeSubscriptionId == null) { log.error("Stripe: el pedido {} de plan llega sin suscripción", o.getId()); return; }
        Optional<ClientSubscription> existing = subscriptions.findByStripeSubscriptionId(stripeSubscriptionId);
        if (existing.isPresent()) { o.setSubscriptionId(existing.get().getId()); return; }
        SubscriptionPlan plan = plans.findById(o.getPlanId()).orElse(null);
        boolean blocked = plan == null || plan.isArchived()
                || subscriptions.existsByClientIdAndPlanIdAndStatusIn(o.getClientId(), o.getPlanId(), List.of("ACTIVE", "PAST_DUE"));
        if (blocked) {
            // Contratado dos veces o plan archivado entretanto: se deja de cobrar y se avisa para reembolsar el primer pago.
            log.warn("Stripe: el pedido {} no puede activar el plan {}; se cancela la suscripción en Stripe y hay que reembolsarla",
                    o.getId(), o.getPlanId());
            payments.cancelSubscription(stripeSubscriptionId);
            return;
        }
        ClientSubscription s = subscriptionService.startFromStripe(o.getTrainerId(), o.getClientId(), plan, o.getAmount(), stripeSubscriptionId);
        o.setSubscriptionId(s.getId());
    }

    private void closeOrder(JsonNode session, String status) {
        order(session).filter(o -> "PENDING".equals(o.getStatus())).ifPresent(o -> o.setStatus(status));
    }

    private Optional<PurchaseOrder> order(JsonNode session) {
        String id = text(session.path("id"));
        Optional<PurchaseOrder> o = id == null ? Optional.empty() : orders.findByStripeSessionId(id);
        if (o.isEmpty()) log.info("Stripe: sesión de Checkout sin pedido propio; se ignora");
        return o;
    }

    // ---------- Suscripciones ----------

    private void invoicePaid(JsonNode invoice) {
        // El primer periodo ya lo aplica checkout.session.completed (evita depender del orden de llegada).
        if ("subscription_create".equals(invoice.path("billing_reason").asText())) return;
        subscription(invoiceSubscription(invoice)).ifPresent(s -> {
            if ("CANCELLED".equals(s.getStatus())) {
                log.warn("Stripe: cobro de la suscripción {} ya cancelada; revisar en Stripe", s.getId());
                return;
            }
            long paid = invoice.path("amount_paid").asLong(-1);
            if (paid >= 0 && paid != OnlinePaymentService.cents(s.getPrice()))
                log.warn("Stripe: la renovación de la suscripción {} cobra {} céntimos y el precio guardado es {}; revisar en Stripe",
                        s.getId(), paid, s.getPrice());
            s.setStatus("ACTIVE");
            subscriptionService.paidPeriodFromStripe(s);
        });
    }

    private void invoiceFailed(JsonNode invoice) {
        subscription(invoiceSubscription(invoice)).filter(s -> "ACTIVE".equals(s.getStatus())).ifPresent(s -> s.setStatus("PAST_DUE"));
    }

    private void subscriptionUpdated(JsonNode sub) {
        subscription(text(sub.path("id"))).filter(s -> !"CANCELLED".equals(s.getStatus())).ifPresent(s -> {
            s.setCancelAtPeriodEnd(sub.path("cancel_at_period_end").asBoolean(false) || text(sub.path("cancel_at")) != null);
            switch (sub.path("status").asText()) {
                case "active", "trialing" -> s.setStatus("ACTIVE");
                case "past_due", "unpaid" -> s.setStatus("PAST_DUE");
                case "canceled" -> cancel(s);
                default -> { }
            }
        });
    }

    private void subscriptionDeleted(JsonNode sub) {
        subscription(text(sub.path("id"))).filter(s -> !"CANCELLED".equals(s.getStatus())).ifPresent(this::cancel);
    }

    private void cancel(ClientSubscription s) {
        s.setStatus("CANCELLED");
        s.setCancelledAt(LocalDateTime.now());
        s.setCancelAtPeriodEnd(false);
    }

    private Optional<ClientSubscription> subscription(String stripeId) {
        return stripeId == null ? Optional.empty() : subscriptions.findByStripeSubscriptionId(stripeId);
    }

    private static String invoiceSubscription(JsonNode invoice) {
        String id = text(invoice.path("subscription"));
        return id != null ? id : text(invoice.path("parent").path("subscription_details").path("subscription"));
    }

    // ---------- Reembolsos ----------

    /** Reembolso total de un comprobante pagado online: se anula. Los parciales y los de renovaciones se revisan a mano. */
    private void refunded(JsonNode charge) {
        String pi = text(charge.path("payment_intent"));
        if (pi == null) return;
        orders.findFirstByStripePaymentIntent(pi).filter(o -> "PAID".equals(o.getStatus())).ifPresentOrElse(o -> {
            if (!charge.path("refunded").asBoolean(false)) { log.info("Stripe: reembolso parcial del pedido {}; revisar a mano", o.getId()); return; }
            o.setStatus("REFUNDED");
            if ("RECEIPT".equals(o.getKind())) receipts.voidByStripeRefund(o.getReceiptId());
        }, () -> log.info("Stripe: reembolso sin pedido de pago único (p. ej. una renovación); anular el comprobante a mano"));
    }

    // ---------- util ----------

    /** Texto de un campo que puede ser un id, un objeto expandido ({id: …}) o null. */
    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isObject()) return text(n.path("id"));
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
