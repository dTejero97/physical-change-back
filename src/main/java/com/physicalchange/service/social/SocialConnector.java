package com.physicalchange.service.social;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Conector de una red social (ADR-014 §1). Uno por red, registrados en SocialConnectorRegistry.
 * En la Fase 8.1 ninguno está activo: el registro usa NotConfiguredConnector para las 8 redes.
 * Lo que cada red necesita (URLs, scopes, PKCE, formatos, métricas) se verifica contra su documentación oficial en T194.
 */
public interface SocialConnector {
    SocialNetwork network();

    /** Hay client id/secret de la red en el entorno. */
    boolean configured();

    Capabilities capabilities();

    /** URL de autorización a la que se redirige el navegador. codeChallenge es null si la red no usa PKCE. */
    String authorizationUrl(String state, String codeChallenge, String redirectUri);

    /** Canje del code (servidor a servidor). */
    TokenSet exchangeCode(String code, String codeVerifier, String redirectUri);

    /** Refresca el token; ConnectorException(EXPIRED) si ya no vale. */
    TokenSet refresh(TokenSet current);

    /** Revoca en la red si lo permite; si no, no hace nada. */
    void revoke(TokenSet tokens);

    PublishResult publish(TokenSet tokens, PublishRequest post);

    List<MetricSnapshot> fetchMetrics(TokenSet tokens, List<String> externalIds);

    // ---------- Tipos ----------

    /** Qué ofrece el conector. publishFormats: formatos de ContentFormat que publica por API. */
    record Capabilities(boolean oauth, boolean pkce, Set<String> publishFormats, boolean metrics, boolean accountMetrics, boolean revoke) {
        public static final Capabilities NONE = new Capabilities(false, false, Set.of(), false, false, false);
        public boolean publish() { return !publishFormats.isEmpty(); }
    }

    /** Tokens en claro solo en memoria. toString() nunca los muestra (ADR-014 §4). */
    record TokenSet(String accessToken, String refreshToken, Instant expiresAt, String scopes, String externalId, String username) {
        @Override public String toString() {
            return "TokenSet[accessToken=***, refreshToken=" + (refreshToken == null ? "null" : "***") + ", expiresAt=" + expiresAt
                    + ", externalId=" + externalId + "]";
        }
    }

    /** Lo que se publica. mediaUrl: URL https (o prefirmada de R2 en la Fase 8.2); mediaFileId: archivo de la Librería. */
    record PublishRequest(Long targetId, String title, String body, List<String> hashtags, String format, Long mediaFileId,
                          String mediaUrl, String mediaContentType) {}

    record PublishResult(String externalId, String externalUrl) {}

    /** Totales acumulados de una publicación en la red; null = la red no lo da. */
    record MetricSnapshot(String externalId, LocalDate measuredOn, Long views, Long reach, Long likes, Long comments, Long shares, Long saves) {}

    /** Errores tipados del conector. El mensaje no lleva tokens ni datos sensibles. */
    class ConnectorException extends RuntimeException {
        public enum Kind { EXPIRED, RATE_LIMITED, REJECTED, TRANSIENT }
        private final Kind kind;
        public ConnectorException(Kind kind, String message) { super(message); this.kind = kind; }
        public Kind kind() { return kind; }
    }
}
