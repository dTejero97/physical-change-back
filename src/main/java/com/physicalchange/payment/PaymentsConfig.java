package com.physicalchange.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * Elige la pasarela con PAYMENTS_PROVIDER: none (por defecto, los endpoints de pago responden 503) o stripe
 * (necesita STRIPE_SECRET_KEY; sin STRIPE_WEBHOOK_SECRET los pagos se inician pero nunca se confirman, por eso se exige).
 */
@Configuration
public class PaymentsConfig {
    @Bean
    PaymentProvider paymentProvider(@Value("${app.payments.provider:none}") String provider,
                                    @Value("${app.payments.stripe.secret-key:}") String secretKey,
                                    @Value("${app.payments.stripe.webhook-secret:}") String webhookSecret,
                                    @Value("${app.payments.stripe.api-version:2024-06-20}") String apiVersion,
                                    ObjectMapper json, Clock clock) {
        return switch (provider.trim().toLowerCase()) {
            case "none" -> new DisabledPaymentProvider();
            case "stripe" -> {
                if (secretKey.isBlank()) throw new IllegalStateException("PAYMENTS_PROVIDER=stripe necesita la variable STRIPE_SECRET_KEY");
                if (secretKey.startsWith("pk_")) throw new IllegalStateException("STRIPE_SECRET_KEY debe ser la clave secreta (sk_…), no la publicable");
                if (webhookSecret.isBlank()) throw new IllegalStateException("PAYMENTS_PROVIDER=stripe necesita la variable STRIPE_WEBHOOK_SECRET");
                yield new StripePaymentProvider(json, secretKey, webhookSecret, apiVersion, clock);
            }
            default -> throw new IllegalStateException("PAYMENTS_PROVIDER debe ser none o stripe");
        };
    }
}
