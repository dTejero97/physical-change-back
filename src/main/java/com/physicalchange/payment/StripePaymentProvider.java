package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stripe por su API REST (ADR-009 §2): {@code https://api.stripe.com/v1/…}, autenticación Bearer con la clave secreta y
 * cuerpo application/x-www-form-urlencoded con claves anidadas ({@code line_items[0][price_data][unit_amount]}).
 * Versión de la API fijada con la cabecera Stripe-Version (STRIPE_API_VERSION) para que las respuestas no cambien solas.
 * Ni la clave ni los cuerpos se escriben en el log: solo el código de estado y el código de error de Stripe.
 */
public class StripePaymentProvider implements PaymentProvider {
    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);
    static final String API = "https://api.stripe.com/v1/";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String secretKey, webhookSecret, apiVersion;
    private final Clock clock;

    public StripePaymentProvider(ObjectMapper json, String secretKey, String webhookSecret, String apiVersion, Clock clock) {
        this.json = json; this.secretKey = secretKey; this.webhookSecret = webhookSecret; this.apiVersion = apiVersion; this.clock = clock;
    }

    @Override public boolean enabled() { return true; }
    @Override public String mode() { return secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_") ? "LIVE" : "TEST"; }
    @Override public boolean webhookConfigured() { return webhookSecret != null && !webhookSecret.isBlank(); }

    @Override
    public String createCustomer(long userId, String email, String name) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("email", email);
        f.put("name", name);
        f.put("metadata[user_id]", String.valueOf(userId));
        return call("POST", "customers", f, "customer-" + userId).path("id").asText();
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest r) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mode", r.mode() == Mode.SUBSCRIPTION ? "subscription" : "payment");
        f.put("customer", r.customerId());
        f.put("client_reference_id", String.valueOf(r.orderId()));
        f.put("metadata[order_id]", String.valueOf(r.orderId()));
        f.put("success_url", r.successUrl());
        f.put("cancel_url", r.cancelUrl());
        f.put("expires_at", String.valueOf(r.expiresAt().getEpochSecond()));
        f.put("locale", "es");
        f.put("line_items[0][quantity]", "1");
        f.put("line_items[0][price_data][currency]", "eur");
        f.put("line_items[0][price_data][unit_amount]", String.valueOf(r.amountCents()));
        f.put("line_items[0][price_data][product_data][name]", r.productName());
        if (r.mode() == Mode.SUBSCRIPTION) {
            f.put("line_items[0][price_data][recurring][interval]", r.interval());
            f.put("line_items[0][price_data][recurring][interval_count]", String.valueOf(r.intervalCount()));
            f.put("subscription_data[metadata][order_id]", String.valueOf(r.orderId()));
        } else {
            f.put("payment_intent_data[metadata][order_id]", String.valueOf(r.orderId()));
        }
        JsonNode s = call("POST", "checkout/sessions", f, r.idempotencyKey());
        return new CheckoutSession(s.path("id").asText(), s.path("url").asText());
    }

    @Override
    public void expireCheckout(String sessionId) {
        try {
            call("POST", "checkout/sessions/" + enc(sessionId) + "/expire", Map.of(), null);
        } catch (PaymentException e) {
            // Ya completada o caducada: no hay nada que cerrar.
            log.info("Stripe: no se ha caducado la sesión de Checkout ({})", e.getMessage());
        }
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        try {
            call("DELETE", "subscriptions/" + enc(subscriptionId), Map.of(), null);
        } catch (PaymentException e) {
            log.warn("Stripe: no se ha cancelado la suscripción ({})", e.getMessage());
            throw e;
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        return call("POST", "billing_portal/sessions", Map.of("customer", customerId, "return_url", returnUrl), null).path("url").asText();
    }

    @Override
    public JsonNode verifyWebhook(byte[] payload, String signatureHeader) {
        if (!webhookConfigured()) throw new InvalidSignatureException("Webhook sin secreto");
        StripeSignature.verify(payload, signatureHeader, webhookSecret, clock);
        try {
            return json.readTree(payload);
        } catch (IOException e) {
            throw new InvalidSignatureException("Cuerpo no válido");
        }
    }

    private JsonNode call(String method, String path, Map<String, String> form, String idempotencyKey) {
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Stripe-Version", apiVersion)
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        b.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                String code = node.path("error").path("code").asText(node.path("error").path("type").asText("desconocido"));
                log.warn("Stripe respondió {} en {} {} (error: {})", res.statusCode(), method, path.replaceAll("/[a-z]+_[A-Za-z0-9_]+", "/…"), code);
                throw new PaymentException(502, "Stripe ha rechazado la operación (" + code + ")");
            }
            return node;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException(502, "No se ha podido contactar con Stripe");
        } catch (IOException e) {
            throw new PaymentException(502, "No se ha podido contactar con Stripe");
        }
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
