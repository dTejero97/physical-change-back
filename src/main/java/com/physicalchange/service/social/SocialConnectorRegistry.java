package com.physicalchange.service.social;

import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Un conector por red (ADR-014 §1). Las redes sin bean propio usan NotConfiguredConnector.
 * Una red solo cuenta como configurada si su conector lo está y además hay SOCIAL_TOKEN_KEY (falla cerrado, ADR-014 §2).
 */
@Component
public class SocialConnectorRegistry {
    private final Map<SocialNetwork, SocialConnector> byNetwork = new EnumMap<>(SocialNetwork.class);
    private final TokenCipher cipher;

    public SocialConnectorRegistry(List<SocialConnector> connectors, TokenCipher cipher) {
        this.cipher = cipher;
        for (SocialNetwork n : SocialNetwork.values()) byNetwork.put(n, new NotConfiguredConnector(n));
        for (SocialConnector c : connectors) {
            if (!(byNetwork.get(c.network()) instanceof NotConfiguredConnector))
                throw new IllegalStateException("Hay dos conectores para " + c.network());
            byNetwork.put(c.network(), c);
        }
    }

    public SocialConnector get(SocialNetwork n) { return byNetwork.get(n); }

    public boolean configured(SocialNetwork n) { return cipher.available() && byNetwork.get(n).configured(); }

    /** Capacidades efectivas: ninguna si la red no está configurada. */
    public SocialConnector.Capabilities capabilities(SocialNetwork n) {
        return configured(n) ? byNetwork.get(n).capabilities() : SocialConnector.Capabilities.NONE;
    }

    /** La red publica ese formato por API (y está configurada). */
    public boolean publishes(SocialNetwork n, String format) { return capabilities(n).publishFormats().contains(format); }
}
