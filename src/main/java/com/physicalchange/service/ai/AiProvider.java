package com.physicalchange.service.ai;

import java.util.List;

/**
 * Proveedor de IA tras una interfaz (ADR-003 §1): Workers AI en producción, uno falso en los tests (nunca se llama a la red).
 * jsonSchema != null pide salida JSON con ese esquema (JSON mode).
 */
public interface AiProvider {
    record Turn(String role, String content) {}
    record Completion(String text, int inputTokens, int outputTokens) {}

    boolean enabled();
    String name();
    String model();
    Completion complete(String system, List<Turn> messages, String jsonSchema, int maxTokens);
}
