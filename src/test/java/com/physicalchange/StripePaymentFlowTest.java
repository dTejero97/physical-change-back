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
import java.nio.charset.StandardCharsets;
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

    /** Simula Stripe sin red: misma verificación de firma que StripePaymentProvider. */
    static class FakeStripe implements PaymentProvider {
        final List<CheckoutRequest> checkouts = new ArrayList<>();
        final List<String> expired = new ArrayList<>(), cancelled = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger();
        final ObjectMapper json = new ObjectMapper();
        @Override public boolean enabled() { return true; }
        @Override public String mode() { return "TEST"; }
        @Override public boolean webhookConfigured() { return true; }
        @Override public String createCustomer(long userId, String email, String name) { return "cus_" + userId; }
        @Override public CheckoutSession createCheckout(CheckoutRequest r) {
            checkouts.add(r);
            String id = "cs_test_" + seq.incrementAndGet();
            return new CheckoutSession(id, "https://checkout.stripe.com/c/pay/" + id);
        }
        @Override public void expireCheckout(String sessionId) { expired.add(sessionId); }
        @Override public void cancelSubscription(String id) { cancelled.add(id); }
        @Override public String createPortalSession(String customerId, String returnUrl) { return "https://billing.stripe.com/p/session/" + customerId; }
        @Override public JsonNode verifyWebhook(byte[] payload, String header) {
            StripeSignature.verify(payload, header, SECRET, Clock.systemUTC());
            try { return json.readTree(payload); } catch (java.io.IOException e) { throw new InvalidSignatureException("Cuerpo no válido"); }
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

    private final LocalDate today = LocalDate.now();
    private Long trainerId, clientId, otherClientId;
    private String trainer, client, otherClient;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        Long otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        otherClient = token(otherClientId, Role.CLIENT);
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
}
