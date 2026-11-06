package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Pasarela de pago (ADR-009 §8, ADR-018): los servicios de negocio solo hablan con esta interfaz.
 * Cada cobro va a la cuenta conectada del profesional (accountId, cabecera Stripe-Account). accountId = null es la cuenta
 * de la plataforma: solo para los pagos anteriores a ADR-018 (legado). Los importes van en céntimos y los calcula el servidor.
 */
public interface PaymentProvider {

    /** Pago único (comprobante) o suscripción periódica (plan). */
    enum Mode { PAYMENT, SUBSCRIPTION }

    /**
     * Datos de una sesión de Checkout. interval/intervalCount solo en SUBSCRIPTION (week|month|year).
     * accountId: cuenta conectada del profesional (null = plataforma, legado). applicationFeeCents: comisión de la
     * plataforma que Stripe descuenta de esa cuenta (0 = ninguna). idempotencyKey evita crear dos sesiones si se reintenta.
     */
    record CheckoutRequest(Mode mode, String customerId, String accountId, long orderId, String productName, long amountCents,
                           long applicationFeeCents, String interval, int intervalCount, String successUrl, String cancelUrl,
                           Instant expiresAt, String idempotencyKey) {}

    record CheckoutSession(String id, String url) {}

    /** false con PAYMENTS_PROVIDER=none: los endpoints responden 503. */
    boolean enabled();

    /** OFF, TEST o LIVE (según el prefijo de la clave; nunca se devuelve la clave). */
    String mode();

    boolean webhookConfigured();

    /** Connect (vincular cuentas de profesionales) configurado: hace falta el client_id de la plataforma para Standard. */
    boolean connectConfigured();

    /** Crea el cliente de Stripe en la cuenta indicada (o en la plataforma si accountId es null); devuelve su id (cus_…). */
    String createCustomer(long userId, String email, String name, String accountId);

    CheckoutSession createCheckout(CheckoutRequest r);

    /**
     * Lee una sesión de Checkout tal como la devuelve Stripe (id, status, payment_status, amount_total, currency,
     * subscription, payment_intent, livemode): el mismo objeto que llega en data.object de checkout.session.*.
     * Para conciliar cuando no llega el webhook (T245). Error de Stripe o de red → PaymentException.
     */
    JsonNode retrieveCheckout(String sessionId, String accountId);

    /** Caduca una sesión abierta para que no se pueda pagar dos veces lo mismo. No falla si ya estaba cerrada. */
    void expireCheckout(String sessionId, String accountId);

    /** Cancela ya la suscripción en Stripe (deja de cobrar). No falla si ya estaba cancelada. */
    void cancelSubscription(String subscriptionId, String accountId);

    /** URL del portal de cliente de Stripe (tarjeta, facturas, cancelar). */
    String createPortalSession(String customerId, String returnUrl, String accountId);

    /** Verifica la firma del webhook de la plataforma ANTES de interpretar el cuerpo. Firma incorrecta → InvalidSignatureException. */
    JsonNode verifyWebhook(byte[] payload, String signatureHeader);

    /** Igual que verifyWebhook, con el secreto del endpoint de cuentas conectadas (ADR-018, punto 6). */
    JsonNode verifyConnectWebhook(byte[] payload, String signatureHeader);

    /** Standard: URL de OAuth de Connect a la que se envía al profesional (state de un solo uso, redirect_uri de la plataforma). */
    String connectAuthorizeUrl(String state, String redirectUri);

    /** Standard: cambia el código de OAuth por el id de la cuenta conectada (acct_…). */
    String exchangeOAuthCode(String code);

    /** Revoca la autorización de una cuenta Standard conectada. No falla si ya estaba revocada. */
    void deauthorize(String accountId);

    /** Express: crea la cuenta conectada que completará el alta; devuelve su id (acct_…). */
    String createExpressAccount(String email, String country);

    /** Express: enlace de alta de Stripe para la cuenta indicada (Account Link). */
    String accountLinkUrl(String accountId, String refreshUrl, String returnUrl);

    /** Cuenta conectada tal como la devuelve Stripe (GET /v1/accounts/{id}). */
    JsonNode retrieveAccount(String accountId);

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String m) { super(m); }
    }
}
