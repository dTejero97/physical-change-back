package com.physicalchange.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * Elige la pasarela con PAYMENTS_PROVIDER: none (por defecto, los endpoints de pago responden 503) o stripe
 * (necesita STRIPE_SECRET_KEY y el secreto del webhook; sin ellos los pagos se iniciarían y nunca se confirmarían).
 * Connect (T280) es opcional al arrancar: sin STRIPE_CONNECT_CLIENT_ID, solo fallan las vinculaciones Standard (503).
 */
@Configuration
public class PaymentsConfig {
    @Bean
    PaymentProvider paymentProvider(@Value("${app.payments.provider:none}") String provider,
                                    @Value("${app.payments.stripe.secret-key:}") String secretKey,
                                    @Value("${app.payments.stripe.webhook-secret:}") String webhookSecret,
                                    @Value("${app.payments.stripe.connect-webhook-secret:}") String connectWebhookSecret,
                                    @Value("${app.payments.stripe.connect-client-id:}") String connectClientId,
                                    @Value("${app.payments.stripe.api-version:2024-06-20}") String apiVersion,
                                    ObjectMapper json, Clock clock) {
        return switch (provider.trim().toLowerCase()) {
            case "none" -> new DisabledPaymentProvider();
            case "stripe" -> {
                if (secretKey.isBlank()) throw new IllegalStateException("PAYMENTS_PROVIDER=stripe necesita la variable STRIPE_SECRET_KEY");
                if (secretKey.startsWith("pk_")) throw new IllegalStateException("STRIPE_SECRET_KEY debe ser la clave secreta (sk_…), no la publicable");
                if (webhookSecret.isBlank()) throw new IllegalStateException("PAYMENTS_PROVIDER=stripe necesita la variable STRIPE_WEBHOOK_SECRET");
                yield new StripePaymentProvider(json, secretKey, webhookSecret, connectWebhookSecret, connectClientId, apiVersion, clock);
            }
            default -> throw new IllegalStateException("PAYMENTS_PROVIDER debe ser none o stripe");
        };
    }
}
