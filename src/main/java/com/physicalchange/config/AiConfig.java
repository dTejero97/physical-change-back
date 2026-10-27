package com.physicalchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.exception.AiException;
import com.physicalchange.service.ai.AiProvider;
import com.physicalchange.service.ai.WorkersAiProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * Elige el proveedor de Physical AI (ADR-003 §12). AI_PROVIDER=none (por defecto): los endpoints responden 503.
 * El token es un secreto de Render; los errores de arranque nombran la variable, nunca su valor.
 */
@Configuration
public class AiConfig {

    @Bean
    public AiProvider aiProvider(@Value("${app.ai.provider:none}") String provider,
                                 @Value("${app.ai.account-id:}") String accountId,
                                 @Value("${app.storage.r2.account-id:}") String r2AccountId,
                                 @Value("${app.ai.api-token:}") String token,
                                 @Value("${app.ai.model:@cf/meta/llama-3.3-70b-instruct-fp8-fast}") String model,
                                 ObjectMapper json) {
        if (!"workers-ai".equals(provider)) return disabled();
        String account = accountId.isBlank() ? r2AccountId : accountId;
        if (account.isBlank()) throw new IllegalStateException("Physical AI: falta CF_ACCOUNT_ID (o R2_ACCOUNT_ID)");
        if (token.isBlank()) throw new IllegalStateException("Physical AI: falta CF_AI_API_TOKEN");
        return new WorkersAiProvider(account, token, model, json);
    }

    private static AiProvider disabled() {
        return new AiProvider() {
            @Override public boolean enabled() { return false; }
            @Override public String name() { return "none"; }
            @Override public String model() { return "—"; }
            @Override public Completion complete(String system, List<Turn> messages, String jsonSchema, int maxTokens) {
                throw new AiException(503, "Physical AI no está configurado");
            }
        };
    }
}
