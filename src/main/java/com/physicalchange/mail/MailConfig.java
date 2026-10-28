package com.physicalchange.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Elige el proveedor con MAIL_PROVIDER: log (por defecto, no envía) o resend (necesita RESEND_API_KEY). */
@Configuration
public class MailConfig {
    @Bean
    MailSender mailSender(@Value("${app.mail.provider:log}") String provider,
                          @Value("${app.mail.resend-api-key:}") String apiKey,
                          @Value("${app.mail.from}") String from,
                          @Value("${app.mail.support}") String support,
                          ObjectMapper json) {
        return switch (provider.trim().toLowerCase()) {
            case "log" -> new LogMailSender();
            case "resend" -> {
                if (apiKey.isBlank()) throw new IllegalStateException("MAIL_PROVIDER=resend necesita la variable RESEND_API_KEY");
                yield new ResendMailSender(json, apiKey, from, support);
            }
            default -> throw new IllegalStateException("MAIL_PROVIDER debe ser log o resend");
        };
    }
}
