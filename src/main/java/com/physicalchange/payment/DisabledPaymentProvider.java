package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;

/** PAYMENTS_PROVIDER=none (por defecto): todo responde 503 y nada cambia. */
public class DisabledPaymentProvider implements PaymentProvider {
    @Override public boolean enabled() { return false; }
    @Override public String mode() { return "OFF"; }
    @Override public boolean webhookConfigured() { return false; }
    @Override public String createCustomer(long userId, String email, String name) { throw PaymentException.disabled(); }
    @Override public CheckoutSession createCheckout(CheckoutRequest r) { throw PaymentException.disabled(); }
    @Override public void expireCheckout(String sessionId) { throw PaymentException.disabled(); }
    @Override public void cancelSubscription(String subscriptionId) { throw PaymentException.disabled(); }
    @Override public String createPortalSession(String customerId, String returnUrl) { throw PaymentException.disabled(); }
    @Override public JsonNode verifyWebhook(byte[] payload, String signatureHeader) { throw PaymentException.disabled(); }
}
