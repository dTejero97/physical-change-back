package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Stripe por su API REST (ADR-009 §2, ADR-018): {@code https://api.stripe.com/v1/…}, autenticación Bearer con la clave
 * secreta de la PLATAFORMA y cuerpo application/x-www-form-urlencoded. Los cobros de un profesional van con la cabecera
 * {@code Stripe-Account} (cobro directo, ADR-018 punto 2). La API de OAuth de Connect vive en connect.stripe.com.
 * Versión de la API fijada con Stripe-Version (STRIPE_API_VERSION). Ni claves ni cuerpos se escriben en el log.
 */
public class StripePaymentProvider implements PaymentProvider {
    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);
    static final String API = "https://api.stripe.com/v1/";
    static final String CONNECT = "https://connect.stripe.com/";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String secretKey, webhookSecret, connectWebhookSecret, connectClientId, apiVersion;
    private final Clock clock;

    public StripePaymentProvider(ObjectMapper json, String secretKey, String webhookSecret, String connectWebhookSecret,
                                 String connectClientId, String apiVersion, Clock clock) {
        this.json = json; this.secretKey = secretKey; this.webhookSecret = webhookSecret;
        this.connectWebhookSecret = connectWebhookSecret; this.connectClientId = connectClientId;
        this.apiVersion = apiVersion; this.clock = clock;
    }

    @Override public boolean enabled() { return true; }
    @Override public String mode() { return secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_") ? "LIVE" : "TEST"; }
    @Override public boolean webhookConfigured() { return webhookSecret != null && !webhookSecret.isBlank(); }
    @Override public boolean connectConfigured() { return connectClientId != null && !connectClientId.isBlank(); }

    @Override
    public String createCustomer(long userId, String email, String name, String accountId) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("email", email);
        f.put("name", name);
        f.put("metadata[user_id]", String.valueOf(userId));
        String key = "customer-" + userId + "-" + (accountId == null ? "platform" : accountId);
        return api("POST", "customers", f, key, accountId).path("id").asText();
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
            // Comisión en cada renovación: porcentaje efectivo del importe (dos decimales). Verificar en T280 (ADR-018).
            if (r.applicationFeeCents() > 0)
                f.put("subscription_data[application_fee_percent]", percent(r.applicationFeeCents(), r.amountCents()));
        } else {
            f.put("payment_intent_data[metadata][order_id]", String.valueOf(r.orderId()));
            if (r.applicationFeeCents() > 0)
                f.put("payment_intent_data[application_fee_amount]", String.valueOf(r.applicationFeeCents()));
        }
        JsonNode s = api("POST", "checkout/sessions", f, r.idempotencyKey(), r.accountId());
        return new CheckoutSession(s.path("id").asText(), s.path("url").asText());
    }

    @Override
    public JsonNode retrieveCheckout(String sessionId, String accountId) {
        return api("GET", "checkout/sessions/" + enc(sessionId), Map.of(), null, accountId);
    }

    @Override
    public void expireCheckout(String sessionId, String accountId) {
        try {
            api("POST", "checkout/sessions/" + enc(sessionId) + "/expire", Map.of(), null, accountId);
        } catch (PaymentException e) {
            // Ya completada o caducada: no hay nada que cerrar.
            log.info("Stripe: no se ha caducado la sesión de Checkout ({})", e.getMessage());
        }
    }

    @Override
    public void cancelSubscription(String subscriptionId, String accountId) {
        try {
            api("DELETE", "subscriptions/" + enc(subscriptionId), Map.of(), null, accountId);
        } catch (PaymentException e) {
            log.warn("Stripe: no se ha cancelado la suscripción ({})", e.getMessage());
            throw e;
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl, String accountId) {
        return api("POST", "billing_portal/sessions", Map.of("customer", customerId, "return_url", returnUrl), null, accountId)
                .path("url").asText();
    }

    @Override
    public JsonNode verifyWebhook(byte[] payload, String signatureHeader) {
        if (!webhookConfigured()) throw new InvalidSignatureException("Webhook sin secreto");
        return verifyWith(payload, signatureHeader, webhookSecret);
    }

    @Override
    public JsonNode verifyConnectWebhook(byte[] payload, String signatureHeader) {
        if (connectWebhookSecret == null || connectWebhookSecret.isBlank()) throw new InvalidSignatureException("Webhook de Connect sin secreto");
        return verifyWith(payload, signatureHeader, connectWebhookSecret);
    }

    private JsonNode verifyWith(byte[] payload, String signatureHeader, String secret) {
        StripeSignature.verify(payload, signatureHeader, secret, clock);
        try {
            return json.readTree(payload);
        } catch (IOException e) {
            throw new InvalidSignatureException("Cuerpo no válido");
        }
    }

    @Override
    public String connectAuthorizeUrl(String state, String redirectUri) {
        if (!connectConfigured()) throw PaymentException.connectNotConfigured();
        return CONNECT + "oauth/authorize?response_type=code&scope=read_write&client_id=" + enc(connectClientId)
                + "&state=" + enc(state) + "&redirect_uri=" + enc(redirectUri);
    }

    @Override
    public String exchangeOAuthCode(String code) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_secret", secretKey);
        f.put("code", code);
        f.put("grant_type", "authorization_code");
        JsonNode n = send("POST", CONNECT + "oauth/token", f, null, Map.of());
        String id = n.path("stripe_user_id").asText("");
        if (id.isBlank()) throw new PaymentException(502, "Stripe no ha devuelto la cuenta del profesional");
        return id;
    }

    @Override
    public void deauthorize(String accountId) {
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("client_id", connectClientId);
            f.put("stripe_user_id", accountId);
            send("POST", CONNECT + "oauth/deauthorize", f, null, Map.of("Authorization", "Bearer " + secretKey));
        } catch (PaymentException e) {
            // Ya revocada o cuenta cerrada en Stripe: lo que importa es que deje de cobrar, y eso ya no es posible.
            log.warn("Stripe: no se ha revocado la autorización de la cuenta ({})", e.getMessage());
            throw e;
        }
    }

    @Override
    public String createExpressAccount(String email, String country) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("type", "express");
        f.put("country", country);
        f.put("email", email);
        f.put("capabilities[card_payments][requested]", "true");
        f.put("capabilities[transfers][requested]", "true");
        return api("POST", "accounts", f, null, null).path("id").asText();
    }

    @Override
    public String accountLinkUrl(String accountId, String refreshUrl, String returnUrl) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("account", accountId);
        f.put("refresh_url", refreshUrl);
        f.put("return_url", returnUrl);
        f.put("type", "account_onboarding");
        return api("POST", "account_links", f, null, null).path("url").asText();
    }

    @Override
    public JsonNode retrieveAccount(String accountId) {
        return api("GET", "accounts/" + enc(accountId), Map.of(), null, null);
    }

    /** Comisión en % con dos decimales, para subscription_data[application_fee_percent]. */
    static String percent(long feeCents, long amountCents) {
        return BigDecimal.valueOf(feeCents).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(amountCents), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private JsonNode api(String method, String path, Map<String, String> form, String idempotencyKey, String accountId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + secretKey);
        headers.put("Stripe-Version", apiVersion);
        if (accountId != null) headers.put("Stripe-Account", accountId);
        return send(method, API + path, form, idempotencyKey, headers);
    }

    /** Llamada HTTP a Stripe con las cabeceras indicadas. Ni la clave ni el cuerpo se registran. */
    private JsonNode send(String method, String url, Map<String, String> form, String idempotencyKey, Map<String, String> headers) {
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded");
        headers.forEach(b::header);
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        b.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                String code = node.path("error").path("code").asText(node.path("error").path("type").asText("desconocido"));
                // message/param los redacta Stripe; no contienen claves ni datos de tarjeta.
                log.warn("Stripe respondió {} en {} {} (error: {}, param: {}, mensaje: {})", res.statusCode(), method,
                        url.replaceAll("/[a-z]+_[A-Za-z0-9_]+", "/…").replaceAll("^https://[^/]+", ""), code,
                        node.path("error").path("param").asText("-"), node.path("error").path("message").asText("-"));
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
