package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.payment.PaymentProvider;
import com.physicalchange.payment.StripeSignature;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pagos online con Stripe (T128, docs/api-pagos-stripe.md v0.2.0, ADR-009) con un proveedor falso: registra las sesiones de
 * Checkout que se crearían y verifica la firma real (StripeSignature) con un secreto de prueba. Los webhooks se firman igual que Stripe.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StripePaymentFlowTest {
    static final String SECRET = "whsec_test_secret";

    /** Simula Stripe sin red: misma verificación de firma que StripePaymentProvider. Las cuentas conectadas se simulan con un JSON. */
    static class FakeStripe implements PaymentProvider {
        final List<CheckoutRequest> checkouts = new ArrayList<>();
        final List<String> expired = new ArrayList<>(), cancelled = new ArrayList<>(), deauthorized = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger();
        final ObjectMapper json = new ObjectMapper();
        /** Cuentas conectadas (acct_…) → JSON de Stripe; por defecto activa con datos completos. */
        final Map<String, String> accounts = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean connect = true;
        @Override public boolean enabled() { return true; }
        @Override public String mode() { return "TEST"; }
        @Override public boolean webhookConfigured() { return true; }
        @Override public boolean connectConfigured() { return connect; }
        @Override public String createCustomer(long userId, String email, String name, String accountId) { return "cus_" + userId; }
        @Override public CheckoutSession createCheckout(CheckoutRequest r) {
            checkouts.add(r);
            String id = "cs_test_" + seq.incrementAndGet();
            return new CheckoutSession(id, "https://checkout.stripe.com/c/pay/" + id);
        }
        /** T245: sesiones tal como las devolvería GET /v1/checkout/sessions/{id} (por defecto, abierta y sin pagar). */
        final Map<String, String> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, AtomicInteger> retrieved = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean failRetrieve;
        @Override public JsonNode retrieveCheckout(String sessionId, String accountId) {
            retrieved.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
            if (failRetrieve) throw new com.physicalchange.payment.PaymentException(502, "No se ha podido contactar con Stripe");
            try {
                return json.readTree(sessions.getOrDefault(sessionId,
                        "{\"id\":\"" + sessionId + "\",\"status\":\"open\",\"payment_status\":\"unpaid\",\"livemode\":false}"));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        int retrievedCount(String sessionId) { AtomicInteger n = retrieved.get(sessionId); return n == null ? 0 : n.get(); }
        @Override public void expireCheckout(String sessionId, String accountId) { expired.add(sessionId); }
        @Override public void cancelSubscription(String id, String accountId) { cancelled.add(id); }
        @Override public String createPortalSession(String customerId, String returnUrl, String accountId) { return "https://billing.stripe.com/p/session/" + customerId; }
        @Override public JsonNode verifyWebhook(byte[] payload, String header) {
            StripeSignature.verify(payload, header, SECRET, Clock.systemUTC());
            try { return json.readTree(payload); } catch (java.io.IOException e) { throw new InvalidSignatureException("Cuerpo no válido"); }
        }
        @Override public JsonNode verifyConnectWebhook(byte[] payload, String header) { return verifyWebhook(payload, header); }
        @Override public String connectAuthorizeUrl(String state, String redirectUri) { return "https://connect.stripe.com/oauth/authorize?state=" + state; }
        /** El código de OAuth "ok-X" devuelve la cuenta acct_X; cualquier otro código falla. */
        @Override public String exchangeOAuthCode(String code) {
            if (!code.startsWith("ok-")) throw new com.physicalchange.payment.PaymentException(502, "código rechazado");
            return "acct_" + code.substring(3);
        }
        @Override public void deauthorize(String accountId) { deauthorized.add(accountId); }
        @Override public String createExpressAccount(String email, String country) { return "acct_express_" + seq.incrementAndGet(); }
        @Override public String accountLinkUrl(String accountId, String refreshUrl, String returnUrl) { return "https://connect.stripe.com/express/" + accountId; }
        @Override public JsonNode retrieveAccount(String accountId) {
            if (accountId.contains("fail")) throw new com.physicalchange.payment.PaymentException(502, "cuenta no disponible");
            try {
                return json.readTree(accounts.getOrDefault(accountId, "{\"id\":\"" + accountId + "\",\"charges_enabled\":true,\"payouts_enabled\":true,"
                        + "\"details_submitted\":true,\"requirements\":{\"currently_due\":[]}}"));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        String lastSession() { return "cs_test_" + seq.get(); }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeStripe fakeStripe() { return new FakeStripe(); }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ReceiptRepository receipts;
    @Autowired PurchaseOrderRepository orders;
    @Autowired ClientSubscriptionRepository subscriptions;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired FakeStripe stripe;
    @Autowired com.physicalchange.service.StripeReconciler reconciler;
    @Autowired TrainerStripeAccountRepository accountRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now();
    private Long trainerId, clientId, otherClientId;
    private String trainer, client, otherClient;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        Long otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        linkTrainer(trainerId);
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        otherClient = token(otherClientId, Role.CLIENT);
    }

    /** Cuenta Stripe activa para el profesional (ADR-018): sin ella no se puede cobrar. */
    private void linkTrainer(Long id) {
        TrainerStripeAccount a = new TrainerStripeAccount();
        a.setTrainerId(id);
        a.setStripeAccountId("acct_t_" + id);
        a.setAccountType("STANDARD");
        a.setStatus("ACTIVE");
        a.setChargesEnabled(true);
        a.setPayoutsEnabled(true);
        a.setDetailsSubmitted(true);
        a.setRequirementsDue("[]");
        a.setLinkedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        accountRepo.save(a);
    }

    // ---------- Pagar un comprobante pendiente ----------

    @Test void pagarComprobanteSoloSeConfirmaConElWebhookFirmado() throws Exception {
        long receipt = pendingReceipt("45.50");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", otherClient, null, 404);   // ajeno
        send("POST", "/api/me/receipts/" + receipt + "/checkout", trainer, null, 403);       // solo clientes
        JsonNode c = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        assertTrue(c.get("checkoutUrl").asText().startsWith("https://checkout.stripe.com/"));
        PaymentProvider.CheckoutRequest r = stripe.checkouts.get(stripe.checkouts.size() - 1);
        assertEquals(PaymentProvider.Mode.PAYMENT, r.mode());
        assertEquals(4550, r.amountCents());                       // importe del servidor
        assertEquals("cus_" + clientId, r.customerId());
        assertEquals(c.get("orderId").asLong(), r.orderId());
        String session = stripe.lastSession();
        long order = c.get("orderId").asLong();

        // La vuelta de Checkout no paga nada: sigue pendiente hasta el webhook.
        assertEquals("PENDING", receipts.findById(receipt).orElseThrow().getStatus());
        assertEquals("PENDING", send("GET", "/api/me/orders/" + order, client, null, 200).get("status").asText());
        send("GET", "/api/me/orders/" + order, otherClient, null, 404);

        // Firma incorrecta, caducada o ausente → 400 y nada cambia.
        String event = sessionCompleted("evt_" + UUID.randomUUID(), session, 4550, "payment");
        webhook(event, StripeSignature.sign(bytes(event), "whsec_otro", now()), 400);
        webhook(event, StripeSignature.sign(bytes(event), SECRET, now() - 600), 400);
        webhook(event, null, 400);
        assertEquals("PENDING", receipts.findById(receipt).orElseThrow().getStatus());

        webhook(event, sign(event), 200);
        Receipt paid = receipts.findById(receipt).orElseThrow();
        assertEquals("PAID", paid.getStatus());
        assertEquals("STRIPE", paid.getMethod());
        assertEquals("PAID", send("GET", "/api/me/orders/" + order, client, null, 200).get("status").asText());

        // Stripe reintenta el mismo evento: 200 sin repetir el efecto.
        webhook(event, sign(event), 200);
        assertEquals("PAID", receipts.findById(receipt).orElseThrow().getStatus());
        // Ya no se puede volver a pagar.
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 409);
    }

    @Test void importeDistintoNoPagaYUnaSegundaSesionCierraLaPrimera() throws Exception {
        long receipt = pendingReceipt("20");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        String first = stripe.lastSession();
        JsonNode second = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        assertTrue(stripe.expired.contains(first));
        assertEquals("EXPIRED", orders.findByStripeSessionId(first).orElseThrow().getStatus());

        String event = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 100, "payment");   // 1 € en vez de 20 €
        webhook(event, sign(event), 200);
        assertEquals("PENDING", receipts.findById(receipt).orElseThrow().getStatus());
        assertEquals("FAILED", orders.findById(second.get("orderId").asLong()).orElseThrow().getStatus());
    }

    @Test void reembolsoTotalAnulaElComprobante() throws Exception {
        long receipt = pendingReceipt("30");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        String event = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 3000, "payment");
        webhook(event, sign(event), 200);
        String refund = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"charge.refunded\",\"data\":{\"object\":"
                + "{\"id\":\"ch_1\",\"payment_intent\":\"pi_" + stripe.lastSession() + "\",\"refunded\":true}}}";
        webhook(refund, sign(refund), 200);
        Receipt x = receipts.findById(receipt).orElseThrow();
        assertEquals("VOID", x.getStatus());
        assertTrue(x.isWasPaid());
        assertEquals("REFUNDED", orders.findByStripeSessionId(stripe.lastSession()).orElseThrow().getStatus());
    }

    // ---------- Contratar un plan con renovación automática ----------

    @Test void contratarPlanRenovarImpagoYCancelar() throws Exception {
        long hidden = plan("Oculto", "40", "MONTHLY", "HIDDEN");
        long plan = plan("Trimestral", "99.90", "QUARTERLY", "ALL");
        JsonNode list = send("GET", "/api/me/plans", client, null, 200);
        assertEquals(1, list.size());
        assertEquals(plan, list.get(0).get("id").asLong());
        assertEquals(0, send("GET", "/api/me/plans", otherClient, null, 200).size());   // planes de otro profesional
        send("POST", "/api/me/plans/" + hidden + "/checkout", client, null, 404);
        send("POST", "/api/me/plans/" + plan + "/checkout", otherClient, null, 404);

        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        PaymentProvider.CheckoutRequest r = stripe.checkouts.get(stripe.checkouts.size() - 1);
        assertEquals(PaymentProvider.Mode.SUBSCRIPTION, r.mode());
        assertEquals(9990, r.amountCents());
        assertEquals("month", r.interval());
        assertEquals(3, r.intervalCount());

        String subId = "sub_" + UUID.randomUUID();
        String completed = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 9990, "subscription")
                .replace("\"subscription\":null", "\"subscription\":\"" + subId + "\"");
        webhook(completed, sign(completed), 200);
        // La factura del alta no crea otro periodo (ya lo hizo checkout.session.completed).
        webhook(invoice("invoice.paid", subId, "subscription_create"), sign(invoice("invoice.paid", subId, "subscription_create")), 200);

        ClientSubscription s = subscriptions.findByStripeSubscriptionId(subId).orElseThrow();
        assertEquals("ACTIVE", s.getStatus());
        assertEquals(today.plusMonths(3), s.getNextPeriodStart());
        List<Receipt> mine = receipts.findAll().stream().filter(x -> s.getId().equals(x.getSubscriptionId())).toList();
        assertEquals(1, mine.size());
        assertEquals("PAID", mine.get(0).getStatus());
        assertEquals("STRIPE", mine.get(0).getMethod());
        JsonNode subs = send("GET", "/api/me/subscriptions", client, null, 200);
        assertTrue(subs.get(0).get("online").asBoolean());
        assertTrue(send("GET", "/api/me/plans", client, null, 200).get(0).get("subscribed").asBoolean());
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 409);   // ya contratado

        // La puesta al día del profesional no emite pendientes para una suscripción de Stripe.
        send("GET", "/api/receipts?statuses=PENDING&from=" + today.minusDays(1) + "&to=" + today.plusDays(1), trainer, null, 200);
        assertEquals(1, receipts.findAll().stream().filter(x -> s.getId().equals(x.getSubscriptionId())).count());

        // Renovación: impago, luego cobro del mismo periodo.
        String failed = invoice("invoice.payment_failed", subId, "subscription_cycle");
        webhook(failed, sign(failed), 200);
        assertEquals("PAST_DUE", subscriptions.findById(s.getId()).orElseThrow().getStatus());
        String renewal = invoice("invoice.paid", subId, "subscription_cycle");
        webhook(renewal, sign(renewal), 200);
        ClientSubscription after = subscriptions.findById(s.getId()).orElseThrow();
        assertEquals("ACTIVE", after.getStatus());
        assertEquals(today.plusMonths(6), after.getNextPeriodStart());
        assertEquals(2, receipts.findAll().stream().filter(x -> s.getId().equals(x.getSubscriptionId()) && "PAID".equals(x.getStatus())).count());

        // Cancelar desde el portal de Stripe al final del periodo y, después, la baja.
        String updated = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":"
                + "{\"id\":\"" + subId + "\",\"status\":\"active\",\"cancel_at_period_end\":true}}}";
        webhook(updated, sign(updated), 200);
        assertTrue(send("GET", "/api/me/subscriptions", client, null, 200).get(0).get("cancelAtPeriodEnd").asBoolean());
        String deleted = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"" + subId + "\"}}}";
        webhook(deleted, sign(deleted), 200);
        assertEquals("CANCELLED", subscriptions.findById(s.getId()).orElseThrow().getStatus());
    }

    @Test void elProfesionalCancelaTambienEnStripe() throws Exception {
        long plan = plan("Mensual", "50", "MONTHLY", "ALL");
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        String subId = "sub_" + UUID.randomUUID();
        String completed = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 5000, "subscription")
                .replace("\"subscription\":null", "\"subscription\":\"" + subId + "\"");
        webhook(completed, sign(completed), 200);
        long id = subscriptions.findByStripeSubscriptionId(subId).orElseThrow().getId();
        send("POST", "/api/subscriptions/" + id + "/cancel", trainer, null, 200);
        assertTrue(stripe.cancelled.contains(subId));
        assertEquals("CANCELLED", subscriptions.findById(id).orElseThrow().getStatus());
    }

    @Test void portalYEstado() throws Exception {
        send("POST", "/api/me/billing-portal", client, null, 409);   // nunca ha pagado con tarjeta
        long receipt = pendingReceipt("10");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        assertTrue(send("POST", "/api/me/billing-portal", client, null, 200).get("url").asText().startsWith("https://billing.stripe.com/"));
        JsonNode st = send("GET", "/api/payments/stripe/status", trainer, null, 200);
        assertEquals("TEST", st.get("mode").asText());
        assertFalse(st.toString().contains("sk_"));
        assertEquals("TEST", send("GET", "/api/payments/stripe/status", client, null, 200).get("mode").asText());
    }

    // ---------- Endurecimiento (T130) ----------

    @Test void pagarAManoCierraLaSesionAbiertaYElPagoTardioNoCobraDosVeces() throws Exception {
        long receipt = pendingReceipt("25");
        long order = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        String session = stripe.lastSession();
        send("POST", "/api/receipts/" + receipt + "/pay", trainer, "{\"method\":\"BIZUM\"}", 200);
        assertTrue(stripe.expired.contains(session));
        assertEquals("EXPIRED", orders.findById(order).orElseThrow().getStatus());
        // Si aun así Stripe confirma el pago (carrera), el comprobante no cambia: sigue pagado por Bizum.
        String event = sessionCompleted("evt_" + UUID.randomUUID(), session, 2500, "payment");
        webhook(event, sign(event), 200);
        Receipt x = receipts.findById(receipt).orElseThrow();
        assertEquals("PAID", x.getStatus());
        assertEquals("BIZUM", x.getMethod());
    }

    @Test void anularCierraLaSesionAbierta() throws Exception {
        long receipt = pendingReceipt("25");
        long order = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        send("POST", "/api/receipts/" + receipt + "/void", trainer, "{\"reason\":\"Error\"}", 200);
        assertEquals("EXPIRED", orders.findById(order).orElseThrow().getStatus());
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 409);
    }

    @Test void eventoDeOtroModoSeIgnora() throws Exception {
        long receipt = pendingReceipt("15");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        String live = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 1500, "payment").replaceFirst("^\\{", "{\"livemode\":true,");
        webhook(live, sign(live), 200);
        assertEquals("PENDING", receipts.findById(receipt).orElseThrow().getStatus());
    }

    @Test void limitesDelWebhookYDeLasSesiones() throws Exception {
        byte[] huge = new byte[1024 * 1024 + 1];
        mvc.perform(post("/api/public/stripe/webhook").contentType("application/json").content(huge)
                .header("Stripe-Signature", StripeSignature.sign(huge, SECRET, now()))).andExpect(status().is(413));

        long receipt = pendingReceipt("12");
        for (int i = 0; i < 10; i++) send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 429);
        // Solo queda abierta la última sesión.
        assertEquals(1, orders.findByReceiptIdAndStatus(receipt, "PENDING").size());
    }

    @Test void pedidoCaducadoSinEventoSeVeCaducado() throws Exception {
        long receipt = pendingReceipt("18");
        long id = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        PurchaseOrder o = orders.findById(id).orElseThrow();
        o.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
        orders.save(o);
        assertEquals("EXPIRED", send("GET", "/api/me/orders/" + id, client, null, 200).get("status").asText());
    }

    // ---------- Conciliación sin webhook (T245) ----------

    @Test void planPagadoSinWebhookSeConciliaAlVolverYElWebhookTardioNoDuplica() throws Exception {
        long plan = plan("Mensual " + UUID.randomUUID(), "49", "MONTHLY", "ALL");
        long order = send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201).get("orderId").asLong();
        String session = stripe.lastSession(), subId = "sub_" + UUID.randomUUID();
        stripe.sessions.put(session, paidSession(session, 4900, subId, false));

        JsonNode o = send("GET", "/api/me/orders/" + order, client, null, 200);
        assertEquals("PAID", o.get("status").asText());
        assertEquals(1, stripe.retrievedCount(session));
        ClientSubscription s = subscriptions.findByStripeSubscriptionId(subId).orElseThrow();
        assertEquals("ACTIVE", s.getStatus());
        assertEquals(s.getId(), orders.findById(order).orElseThrow().getSubscriptionId());
        // T250: el alta por pago también avisa al profesional (plan sin programa → "Aún no tiene entrenamiento").
        JsonNode bell = send("GET", "/api/notifications", trainer, null, 200);
        assertTrue(java.util.stream.StreamSupport.stream((bell.has("content") ? bell.get("content") : bell).spliterator(), false)
                .anyMatch(n -> "SUBSCRIPTION_STARTED".equals(n.get("type").asText()) && n.get("refId").asLong() == clientId));

        // El webhook llega después: 200, sin segunda suscripción ni segundo comprobante.
        String late = sessionCompleted("evt_" + UUID.randomUUID(), session, 4900, "subscription")
                .replace("\"subscription\":null", "\"subscription\":\"" + subId + "\"");
        webhook(late, sign(late), 200);
        assertEquals(1, subscriptions.findAll().stream().filter(x -> subId.equals(x.getStripeSubscriptionId())).count());
        assertEquals(1, receipts.findAll().stream().filter(x -> s.getId().equals(x.getSubscriptionId())).count());
        assertTrue(stripe.cancelled.stream().noneMatch(subId::equals));   // no se tomó por contratación doble

        // Ya PAID: no se vuelve a consultar a Stripe.
        send("GET", "/api/me/orders/" + order, client, null, 200);
        assertEquals(1, stripe.retrievedCount(session));
    }

    @Test void comprobanteConWebhookPrevioNoConsultaStripe() throws Exception {
        long receipt = pendingReceipt("30");
        long order = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        String session = stripe.lastSession();
        String event = sessionCompleted("evt_" + UUID.randomUUID(), session, 3000, "payment");
        webhook(event, sign(event), 200);
        assertEquals("PAID", send("GET", "/api/me/orders/" + order, client, null, 200).get("status").asText());
        assertEquals(0, stripe.retrievedCount(session));
        assertEquals("PAID", receipts.findById(receipt).orElseThrow().getStatus());
    }

    @Test void sesionAbiertaNoCambiaYSoloSeConsultaUnaVezCada10sYNuncaParaUnAjeno() throws Exception {
        long receipt = pendingReceipt("20");
        long order = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        String session = stripe.lastSession();
        send("GET", "/api/me/orders/" + order, otherClient, null, 404);
        assertEquals(0, stripe.retrievedCount(session));                    // lo ajeno no llega a Stripe
        assertEquals("PENDING", send("GET", "/api/me/orders/" + order, client, null, 200).get("status").asText());
        assertEquals("PENDING", send("GET", "/api/me/orders/" + order, client, null, 200).get("status").asText());
        assertEquals(1, stripe.retrievedCount(session));                    // límite de 10 s
        assertEquals("PENDING", receipts.findById(receipt).orElseThrow().getStatus());
    }

    @Test void sesionCaducadaEnStripePasaAExpiredYStripeCaidoNoRompeLaRespuesta() throws Exception {
        long r1 = pendingReceipt("21");
        long expiredOrder = send("POST", "/api/me/receipts/" + r1 + "/checkout", client, null, 201).get("orderId").asLong();
        String s1 = stripe.lastSession();
        stripe.sessions.put(s1, "{\"id\":\"" + s1 + "\",\"status\":\"expired\",\"payment_status\":\"unpaid\",\"livemode\":false}");
        assertEquals("EXPIRED", send("GET", "/api/me/orders/" + expiredOrder, client, null, 200).get("status").asText());
        assertEquals("EXPIRED", orders.findById(expiredOrder).orElseThrow().getStatus());
        assertEquals("PENDING", receipts.findById(r1).orElseThrow().getStatus());   // el comprobante sigue pendiente de pago

        long r2 = pendingReceipt("22");
        long down = send("POST", "/api/me/receipts/" + r2 + "/checkout", client, null, 201).get("orderId").asLong();
        stripe.failRetrieve = true;
        try {
            assertEquals("PENDING", send("GET", "/api/me/orders/" + down, client, null, 200).get("status").asText());
        } finally {
            stripe.failRetrieve = false;
        }
    }

    @Test void conciliacionNoConcedeConImporteDistintoNiConOtroModoNiConSesionAjena() throws Exception {
        // Importe distinto → FAILED, nada se concede.
        long r1 = pendingReceipt("40");
        long o1 = send("POST", "/api/me/receipts/" + r1 + "/checkout", client, null, 201).get("orderId").asLong();
        String s1 = stripe.lastSession();
        stripe.sessions.put(s1, paidSession(s1, 100, null, false));
        assertEquals("FAILED", send("GET", "/api/me/orders/" + o1, client, null, 200).get("status").asText());
        assertEquals("PENDING", receipts.findById(r1).orElseThrow().getStatus());

        // Sesión de modo real con clave de pruebas → se ignora.
        long r2 = pendingReceipt("41");
        long o2 = send("POST", "/api/me/receipts/" + r2 + "/checkout", client, null, 201).get("orderId").asLong();
        String s2 = stripe.lastSession();
        stripe.sessions.put(s2, paidSession(s2, 4100, null, true));
        assertEquals("PENDING", send("GET", "/api/me/orders/" + o2, client, null, 200).get("status").asText());

        // Stripe devuelve una sesión que no es la del pedido → se ignora.
        long r3 = pendingReceipt("42");
        long o3 = send("POST", "/api/me/receipts/" + r3 + "/checkout", client, null, 201).get("orderId").asLong();
        String s3 = stripe.lastSession();
        stripe.sessions.put(s3, paidSession(s1, 4200, null, false));
        assertEquals("PENDING", send("GET", "/api/me/orders/" + o3, client, null, 200).get("status").asText());
        assertEquals("PENDING", receipts.findById(r3).orElseThrow().getStatus());
    }

    @Test void tareaProgramadaConciliaPedidosPendientesAunqueElClienteNoVuelva() throws Exception {
        long receipt = pendingReceipt("33");
        long order = send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201).get("orderId").asLong();
        String session = stripe.lastSession();
        stripe.sessions.put(session, paidSession(session, 3300, null, false));

        reconciler.reconcilePending();                                      // recién creado (< 2 min): no se toca
        assertEquals(0, stripe.retrievedCount(session));

        jdbc.update("update purchase_order set created_at = ? where id = ?", java.time.LocalDateTime.now().minusMinutes(5), order);
        reconciler.reconcilePending();
        assertEquals("PAID", orders.findById(order).orElseThrow().getStatus());
        assertEquals("PAID", receipts.findById(receipt).orElseThrow().getStatus());
        assertEquals("STRIPE", receipts.findById(receipt).orElseThrow().getMethod());
    }

    // ---------- Firma (unitario) ----------

    @Test void firmaAceptaRotacionYRechazaManipulacion() {
        byte[] body = bytes("{\"id\":\"evt_1\"}");
        long t = now();
        String good = StripeSignature.sign(body, SECRET, t);
        String rotated = "t=" + t + ",v1=" + "0".repeat(64) + "," + good.substring(good.indexOf("v1="));
        StripeSignature.verify(body, rotated, SECRET, Clock.systemUTC());
        assertThrows(PaymentProvider.InvalidSignatureException.class,
                () -> StripeSignature.verify(bytes("{\"id\":\"evt_2\"}"), good, SECRET, Clock.systemUTC()));
        assertThrows(PaymentProvider.InvalidSignatureException.class,
                () -> StripeSignature.verify(body, "v1=abc", SECRET, Clock.systemUTC()));
        assertThrows(PaymentProvider.InvalidSignatureException.class,
                () -> StripeSignature.verify(body, "t=x,v1=abc", SECRET, Clock.systemUTC()));
    }

    // ---------- util ----------

    private long pendingReceipt(String price) throws Exception {
        String body = "{\"clientId\":" + clientId + ",\"issueDate\":\"" + today + "\",\"method\":\"OTHER\",\"status\":\"PENDING\",\"lines\":["
                + "{\"kind\":\"FREE\",\"name\":\"Sesión\",\"unitPrice\":" + price + ",\"quantity\":1,\"taxRate\":21}]}";
        return send("POST", "/api/receipts", trainer, body, 201).get("id").asLong();
    }

    private long plan(String name, String price, String frequency, String visibility) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"description\":\"Descripción\",\"price\":" + price + ",\"taxRate\":21,\"cover\":\"brand\","
                + "\"bonuses\":[],\"autoTagIds\":[],\"frequency\":\"" + frequency + "\",\"visibility\":\"" + visibility + "\"}";
        return send("POST", "/api/subscription-plans", trainer, body, 201).get("id").asLong();
    }

    private String sessionCompleted(String eventId, String session, long amount, String mode) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"" + session + "\","
                + "\"object\":\"checkout.session\",\"mode\":\"" + mode + "\",\"payment_status\":\"paid\",\"amount_total\":" + amount + ","
                + "\"currency\":\"eur\",\"payment_intent\":" + ("payment".equals(mode) ? "\"pi_" + session + "\"" : "null")
                + ",\"subscription\":null}}}";
    }

    /** Sesión completada y pagada tal como la devuelve GET /v1/checkout/sessions/{id} (T245). */
    private static String paidSession(String session, long amount, String subId, boolean livemode) {
        return "{\"id\":\"" + session + "\",\"object\":\"checkout.session\",\"status\":\"complete\",\"payment_status\":\"paid\","
                + "\"amount_total\":" + amount + ",\"currency\":\"eur\",\"livemode\":" + livemode + ","
                + "\"payment_intent\":" + (subId == null ? "\"pi_" + session + "\"" : "null") + ","
                + "\"subscription\":" + (subId == null ? "null" : "\"" + subId + "\"") + "}";
    }

    /** Formato de las versiones de 2025: la suscripción va en parent.subscription_details. */
    private String invoice(String type, String subId, String reason) {
        return "{\"id\":\"evt_" + UUID.nameUUIDFromBytes((type + subId + reason).getBytes()) + "\",\"type\":\"" + type + "\",\"data\":{\"object\":"
                + "{\"id\":\"in_1\",\"billing_reason\":\"" + reason + "\",\"parent\":{\"subscription_details\":{\"subscription\":\"" + subId + "\"}}}}}";
    }

    private void webhook(String body, String signature, int status) throws Exception {
        var req = post("/api/public/stripe/webhook").contentType("application/json").content(bytes(body));
        if (signature != null) req.header("Stripe-Signature", signature);
        mvc.perform(req).andExpect(status().is(status));
    }

    private String sign(String body) { return StripeSignature.sign(bytes(body), SECRET, now()); }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    // ---------- ADR-018: cuenta Stripe por profesional y comisión por plan ----------

    private long planWithFee(String price, String fee) throws Exception {
        String body = "{\"name\":\"Con comisión\",\"description\":\"Descripción\",\"price\":" + price + ",\"taxRate\":21,\"cover\":\"brand\","
                + "\"bonuses\":[],\"autoTagIds\":[],\"frequency\":\"MONTHLY\",\"visibility\":\"ALL\",\"platformFeePercent\":" + fee + "}";
        return send("POST", "/api/subscription-plans", trainer, body, 201).get("id").asLong();
    }

    private JsonNode planOf(String auth, long planId) throws Exception {
        for (JsonNode p : send("GET", "/api/me/plans", auth, null, 200)) if (p.get("id").asLong() == planId) return p;
        throw new AssertionError("El plan " + planId + " no aparece en el catálogo del cliente");
    }

    /** El cliente paga precio + comisión; la comisión va a la plataforma vía application_fee y el cobro a la cuenta del profesional. */
    @Test void cobroVaALaCuentaDelProfesionalYLaComisionLaPagaElCliente() throws Exception {
        long plan = planWithFee("50", "21");
        JsonNode mine = planOf(client, plan);
        assertTrue(mine.get("payable").asBoolean());
        assertEquals(0, new BigDecimal("10.50").compareTo(mine.get("feeAmount").decimalValue()));
        assertEquals(0, new BigDecimal("60.50").compareTo(mine.get("totalPrice").decimalValue()));
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        PaymentProvider.CheckoutRequest r = stripe.checkouts.get(stripe.checkouts.size() - 1);
        assertEquals("acct_t_" + trainerId, r.accountId());
        assertEquals(6050, r.amountCents());
        assertEquals(1050, r.applicationFeeCents());
        assertEquals(PaymentProvider.Mode.SUBSCRIPTION, r.mode());
    }

    /** Sin cuenta activa no hay cobro (ADR-018, punto 4); el plan sigue visible pero no se puede pagar. */
    @Test void sinCuentaActivaNoSeCobra() throws Exception {
        long receipt = pendingReceipt("30");
        long plan = planWithFee("20", "10");
        accountRepo.deleteById(trainerId);
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 409);
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 409);
        assertFalse(planOf(client, plan).get("payable").asBoolean());
    }

    /** El webhook de cuentas conectadas actualiza el estado; sin cargos habilitados no se cobra y al habilitarse sí. */
    @Test void webhookDeCuentaActualizaElEstadoYSinCargosNoSeCobra() throws Exception {
        String acct = "acct_t_" + trainerId;
        String pending = accountEvent(acct, false, "[\"individual.verification.document\"]");
        connectWebhook(pending, sign(pending), 200);
        JsonNode st = send("GET", "/api/payments/stripe/account", trainer, null, 200);
        assertEquals("PENDING_DETAILS", st.get("status").asText());
        assertFalse(st.has("stripeAccountId"));                       // el id de cuenta nunca sale en la respuesta
        assertEquals("individual.verification.document", st.get("requirementsDue").get(0).asText());
        long receipt = pendingReceipt("30");
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 409);
        String ok = accountEvent(acct, true, "[]");
        connectWebhook(ok, sign(ok), 200);
        assertEquals("ACTIVE", send("GET", "/api/payments/stripe/account", trainer, null, 200).get("status").asText());
        send("POST", "/api/me/receipts/" + receipt + "/checkout", client, null, 201);
    }

    /** Vinculación Standard por OAuth: state de un solo uso, cuenta única por profesional y vuelta con error. */
    @Test void vinculacionStandardPorOAuthYErroresDeLaVuelta() throws Exception {
        long t2 = save("t2-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.TRAINER, null).getId();
        String tok2 = token(t2, Role.TRAINER);
        String state = linkState(tok2);
        assertTrue(callback("ok-" + t2, state, null).contains("stripe=ok"));
        JsonNode st = send("GET", "/api/payments/stripe/account", tok2, null, 200);
        assertEquals("ACTIVE", st.get("status").asText());
        assertEquals("STANDARD", st.get("accountType").asText());
        assertFalse(st.has("stripeAccountId"));
        send("POST", "/api/payments/stripe/account/link", tok2, "{\"accountType\":\"STANDARD\"}", 409);   // ya vinculada
        assertTrue(callback("ok-" + t2, state, null).contains("motivo=state"));                           // el state ya se usó
        assertTrue(callback("ok-x", "no-existe", null).contains("motivo=state"));
        long t3 = save("t3-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.TRAINER, null).getId();
        String tok3 = token(t3, Role.TRAINER);
        assertTrue(callback("ok-t_" + trainerId, linkState(tok3), null).contains("motivo=cuenta_en_uso")); // cuenta de otro profesional
        assertTrue(callback("bad", linkState(tok3), null).contains("motivo=oauth"));
        assertTrue(callback(null, linkState(tok3), "access_denied").contains("motivo=cancelado"));
    }

    /** Desvincular: con una suscripción activa en la cuenta se rechaza; cancelada, se revoca en Stripe y se desvincula. */
    @Test void desvincularBloqueaConSuscripcionActivaYRevocaSinElla() throws Exception {
        long plan = plan("Mensual " + UUID.randomUUID(), "49", "MONTHLY", "ALL");
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        String subId = "sub_" + UUID.randomUUID();
        String completed = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 4900, "subscription")
                .replace("\"subscription\":null", "\"subscription\":\"" + subId + "\"");
        webhook(completed, sign(completed), 200);
        send("DELETE", "/api/payments/stripe/account", trainer, null, 409);
        long id = subscriptions.findByStripeSubscriptionId(subId).orElseThrow().getId();
        send("POST", "/api/subscriptions/" + id + "/cancel", trainer, null, 200);
        send("DELETE", "/api/payments/stripe/account", trainer, null, 204);
        assertTrue(stripe.deauthorized.contains("acct_t_" + trainerId));
        assertEquals("NOT_LINKED", send("GET", "/api/payments/stripe/account", trainer, null, 200).get("status").asText());
    }

    private String linkState(String auth) throws Exception {
        String url = send("POST", "/api/payments/stripe/account/link", auth, "{\"accountType\":\"STANDARD\"}", 201).get("url").asText();
        return url.substring(url.indexOf("state=") + 6);
    }

    /** Vuelve de Stripe; devuelve la URL de redirección (siempre 302, nunca JSON). */
    private String callback(String code, String state, String error) throws Exception {
        var req = get("/api/public/stripe/connect/callback").param("state", state);
        if (code != null) req.param("code", code);
        if (error != null) req.param("error", error);
        return mvc.perform(req).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
    }

    private String accountEvent(String acct, boolean charges, String due) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"account.updated\",\"account\":\"" + acct + "\",\"livemode\":false,"
                + "\"data\":{\"object\":{\"id\":\"" + acct + "\",\"charges_enabled\":" + charges + ",\"payouts_enabled\":" + charges
                + ",\"details_submitted\":true,\"requirements\":{\"currently_due\":" + due + "}}}}";
    }

    private void connectWebhook(String body, String signature, int status) throws Exception {
        var req = post("/api/public/stripe/connect-webhook").contentType("application/json").content(bytes(body));
        if (signature != null) req.header("Stripe-Signature", signature);
        mvc.perform(req).andExpect(status().is(status));
    }

    // ---------- T282: QA y seguridad de la vinculación y los cobros (ADR-018) ----------

    /** Un cliente solo ve y paga planes de su profesional: un plan ajeno responde 404 y no aparece en su catálogo. */
    @Test void planAjenoNoSeContrataNiSeVe() throws Exception {
        long otherTrainer = save("ot2-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.TRAINER, null).getId();
        linkTrainer(otherTrainer);
        String body = "{\"name\":\"Ajeno\",\"description\":\"D\",\"price\":30,\"taxRate\":21,\"cover\":\"brand\",\"bonuses\":[],"
                + "\"autoTagIds\":[],\"frequency\":\"MONTHLY\",\"visibility\":\"ALL\"}";
        long foreign = send("POST", "/api/subscription-plans", token(otherTrainer, Role.TRAINER), body, 201).get("id").asLong();
        send("POST", "/api/me/plans/" + foreign + "/checkout", client, null, 404);
        for (JsonNode p : send("GET", "/api/me/plans", client, null, 200)) assertNotEquals(foreign, p.get("id").asLong());
    }

    /** Un cliente no accede a la cuenta de Stripe de ningún profesional (solo TRAINER y ADMIN). */
    @Test void clienteNoAccedeALaCuentaDeStripe() throws Exception {
        send("GET", "/api/payments/stripe/account", client, null, 403);
        send("POST", "/api/payments/stripe/account/link", client, "{}", 403);
        send("POST", "/api/payments/stripe/account/refresh", client, null, 403);
        send("DELETE", "/api/payments/stripe/account", client, null, 403);
    }

    /** El cliente no elige cuenta ni comisión: el servidor las toma del plan y de la cuenta del profesional. */
    @Test void elClienteNoPuedeElegirCuentaNiComision() throws Exception {
        long plan = planWithFee("50", "21");
        send("POST", "/api/me/plans/" + plan + "/checkout", client,
                "{\"accountId\":\"acct_ajena\",\"trainerId\":" + otherClientId + ",\"platformFeePercent\":0,\"applicationFeeCents\":0}", 201);
        PaymentProvider.CheckoutRequest r = stripe.checkouts.get(stripe.checkouts.size() - 1);
        assertEquals("acct_t_" + trainerId, r.accountId());
        assertEquals(1050, r.applicationFeeCents());
        assertEquals(6050, r.amountCents());
    }

    /** El id de cuenta nunca sale en las respuestas de profesional ni de cliente. */
    @Test void elIdDeCuentaNoSaleEnLasRespuestas() throws Exception {
        long plan = planWithFee("50", "21");
        pendingReceipt("10");
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        List<String> bodies = List.of(
                send("GET", "/api/payments/stripe/account", trainer, null, 200).toString(),
                send("GET", "/api/payments/stripe/status", trainer, null, 200).toString(),
                send("GET", "/api/me/plans", client, null, 200).toString(),
                send("GET", "/api/me/orders", client, null, 200).toString(),
                send("GET", "/api/me/subscriptions", client, null, 200).toString(),
                send("POST", "/api/payments/stripe/account/refresh", trainer, null, 200).toString());
        for (String b : bodies) assertFalse(b.contains("acct_t_" + trainerId), b);
    }

    /** Webhook de cuentas: firma incorrecta → 400 y nada cambia; evento de una cuenta desconocida → 200 y sin efecto. */
    @Test void webhookDeCuentaNoVinculadaOFirmaMalaNoCambiaNada() throws Exception {
        String ghost = "acct_ghost_" + UUID.randomUUID().toString().substring(0, 8);
        String unknown = accountEvent(ghost, true, "[]");
        connectWebhook(unknown, sign(unknown), 200);
        assertFalse(accountRepo.findByStripeAccountId(ghost).isPresent());
        String forged = accountEvent("acct_t_" + trainerId, false, "[]");
        connectWebhook(forged, StripeSignature.sign(bytes(forged), "whsec_otro", now()), 400);
        assertEquals("ACTIVE", accountRepo.findById(trainerId).orElseThrow().getStatus());
        assertTrue(accountRepo.findById(trainerId).orElseThrow().isChargesEnabled());
    }

    /** Revocación desde Stripe: la cuenta se desvincula y las suscripciones activas pasan a impago para revisarlas. */
    @Test void revocacionEnStripeDesvinculaYPasaSuscripcionesAImpago() throws Exception {
        long subId = contratarPlanConSuscripcion();
        String revoked = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"account.application.deauthorized\",\"account\":\"acct_t_"
                + trainerId + "\",\"livemode\":false,\"data\":{\"object\":{\"id\":\"ca_test\"}}}";
        connectWebhook(revoked, sign(revoked), 200);
        assertFalse(accountRepo.findById(trainerId).isPresent());
        assertEquals("PAST_DUE", subscriptions.findById(subId).orElseThrow().getStatus());
        assertEquals("NOT_LINKED", send("GET", "/api/payments/stripe/account", trainer, null, 200).get("status").asText());
    }

    /** Un impago (PAST_DUE) también bloquea desvincular: la suscripción sigue cobrándose en esa cuenta. */
    @Test void impagoTambienBloqueaDesvincular() throws Exception {
        long subId = contratarPlanConSuscripcion();
        ClientSubscription s = subscriptions.findById(subId).orElseThrow();
        s.setStatus("PAST_DUE");
        subscriptions.save(s);
        send("DELETE", "/api/payments/stripe/account", trainer, null, 409);
    }

    /** Comprobar el estado en Stripe admite 10 veces por hora (contrato): la 11.ª responde 429. */
    @Test void comprobarEstadoTieneLimiteDeFrecuencia() throws Exception {
        for (int i = 0; i < 10; i++) send("POST", "/api/payments/stripe/account/refresh", trainer, null, 200);
        send("POST", "/api/payments/stripe/account/refresh", trainer, null, 429);
    }

    /** Crea un plan, lo contrata y completa el primer cobro por webhook: devuelve el id de la suscripción local. */
    private long contratarPlanConSuscripcion() throws Exception {
        long plan = plan("Mensual " + UUID.randomUUID(), "49", "MONTHLY", "ALL");
        send("POST", "/api/me/plans/" + plan + "/checkout", client, null, 201);
        String subId = "sub_" + UUID.randomUUID();
        String completed = sessionCompleted("evt_" + UUID.randomUUID(), stripe.lastSession(), 4900, "subscription")
                .replace("\"subscription\":null", "\"subscription\":\"" + subId + "\"");
        webhook(completed, sign(completed), 200);
        return subscriptions.findByStripeSubscriptionId(subId).orElseThrow().getId();
    }

    /** Vincular admite 10 intentos por hora y profesional: el siguiente responde 429 y no crea nada en Stripe. */
    @Test void vincularTieneLimiteDeIntentos() throws Exception {
        long t = save("lim-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.TRAINER, null).getId();
        String tok = token(t, Role.TRAINER);
        for (int i = 0; i < 10; i++) send("POST", "/api/payments/stripe/account/link", tok, "{\"accountType\":\"STANDARD\"}", 201);
        send("POST", "/api/payments/stripe/account/link", tok, "{\"accountType\":\"STANDARD\"}", 429);
    }

    /** Si Stripe concede la autorización pero no podemos leer la cuenta, se revoca: no queda autorizada una cuenta sin guardar. */
    @Test void autorizacionSinCuentaLegibleSeRevoca() throws Exception {
        long t = save("fail-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.TRAINER, null).getId();
        String tok = token(t, Role.TRAINER);
        String state = linkState(tok);
        assertTrue(callback("ok-fail-" + t, state, null).contains("motivo=oauth"));
        assertTrue(stripe.deauthorized.contains("acct_fail-" + t));
        assertEquals("NOT_LINKED", send("GET", "/api/payments/stripe/account", tok, null, 200).get("status").asText());
    }
}
