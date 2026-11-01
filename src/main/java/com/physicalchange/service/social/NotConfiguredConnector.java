package com.physicalchange.service.social;

import java.util.List;

/** Red sin app de desarrollador ni claves en el servidor (Fase 8.1): no se conecta, no publica y no sincroniza. */
public final class NotConfiguredConnector implements SocialConnector {
    private final SocialNetwork network;
    public NotConfiguredConnector(SocialNetwork network) { this.network = network; }

    @Override public SocialNetwork network() { return network; }
    @Override public boolean configured() { return false; }
    @Override public Capabilities capabilities() { return Capabilities.NONE; }
    @Override public String authorizationUrl(String state, String codeChallenge, String redirectUri) { throw unavailable(); }
    @Override public TokenSet exchangeCode(String code, String codeVerifier, String redirectUri) { throw unavailable(); }
    @Override public TokenSet refresh(TokenSet current) { throw new ConnectorException(ConnectorException.Kind.EXPIRED, "Red no disponible"); }
    @Override public void revoke(TokenSet tokens) { }
    @Override public PublishResult publish(TokenSet tokens, PublishRequest post) { throw unavailable(); }
    @Override public List<MetricSnapshot> fetchMetrics(TokenSet tokens, List<String> externalIds) { return List.of(); }

    private static IllegalStateException unavailable() { return new IllegalStateException("Red no configurada"); }
}
