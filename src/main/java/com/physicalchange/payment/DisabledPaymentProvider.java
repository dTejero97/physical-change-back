package com.physicalchange.payment;

import com.fasterxml.jackson.databind.JsonNode;

/** PAYMENTS_PROVIDER=none (por defecto): todo responde 503 y nada cambia. */
public class DisabledPaymentProvider implements PaymentProvider {
    @Override public boolean enabled() { return false; }
    @Override public String mode() { return "OFF"; }
    @Override public boolean webhookConfigured() { return false; }
    @Override public boolean connectConfigured() { return false; }
    @Override public String createCustomer(long userId, String email, String name, String accountId) { throw PaymentException.disabled(); }
    @Override public CheckoutSession createCheckout(CheckoutRequest r) { throw PaymentException.disabled(); }
    @Override public JsonNode retrieveCheckout(String sessionId, String accountId) { throw PaymentException.disabled(); }
    @Override public void expireCheckout(String sessionId, String accountId) { throw PaymentException.disabled(); }
    @Override public void cancelSubscription(String subscriptionId, String accountId) { throw PaymentException.disabled(); }
    @Override public String createPortalSession(String customerId, String returnUrl, String accountId) { throw PaymentException.disabled(); }
    @Override public JsonNode verifyWebhook(byte[] payload, String signatureHeader) { throw PaymentException.disabled(); }
    @Override public JsonNode verifyConnectWebhook(byte[] payload, String signatureHeader) { throw PaymentException.disabled(); }
    @Override public String connectAuthorizeUrl(String state, String redirectUri) { throw PaymentException.disabled(); }
    @Override public String exchangeOAuthCode(String code) { throw PaymentException.disabled(); }
    @Override public void deauthorize(String accountId) { throw PaymentException.disabled(); }
    @Override public String createExpressAccount(String email, String country) { throw PaymentException.disabled(); }
    @Override public String accountLinkUrl(String accountId, String refreshUrl, String returnUrl) { throw PaymentException.disabled(); }
    @Override public JsonNode retrieveAccount(String accountId) { throw PaymentException.disabled(); }
}
