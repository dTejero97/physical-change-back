package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Pasarela de pago (ADR-009 §8): los servicios de negocio solo hablan con esta interfaz. Hoy hay una sola cuenta de
 * Stripe (sin Connect); si entran más profesionales, la cuenta se elegiría aquí sin tocar los servicios.
 * Los importes van siempre en céntimos de euro y los calcula el servidor.
 */
public interface PaymentProvider {

    /** Pago único (comprobante) o suscripción periódica (plan). */
    enum Mode { PAYMENT, SUBSCRIPTION }

    /**
     * Datos de una sesión de Checkout. interval/intervalCount solo en SUBSCRIPTION (week|month|year).
     * idempotencyKey evita crear dos sesiones si se reintenta la misma petición.
     */
    record CheckoutRequest(Mode mode, String customerId, long orderId, String productName, long amountCents,
                           String interval, int intervalCount, String successUrl, String cancelUrl, Instant expiresAt,
                           String idempotencyKey) {}

    record CheckoutSession(String id, String url) {}

    /** false con PAYMENTS_PROVIDER=none: los endpoints responden 503. */
    boolean enabled();

    /** OFF, TEST o LIVE (según el prefijo de la clave; nunca se devuelve la clave). */
    String mode();

    boolean webhookConfigured();

    /** Crea el cliente de Stripe; devuelve su id (cus_…). */
    String createCustomer(long userId, String email, String name);

    CheckoutSession createCheckout(CheckoutRequest r);

    /** Caduca una sesión abierta para que no se pueda pagar dos veces lo mismo. No falla si ya estaba cerrada. */
    void expireCheckout(String sessionId);

    /** Cancela ya la suscripción en Stripe (deja de cobrar). No falla si ya estaba cancelada. */
    void cancelSubscription(String subscriptionId);

    /** URL del portal de cliente de Stripe (tarjeta, facturas, cancelar). */
    String createPortalSession(String customerId, String returnUrl);

    /**
     * Verifica la firma del webhook ANTES de interpretar el cuerpo y devuelve el evento.
     * Firma ausente, mal formada, caducada o incorrecta → InvalidSignatureException.
     */
    JsonNode verifyWebhook(byte[] payload, String signatureHeader);

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String m) { super(m); }
    }
}
