package com.physicalchange.service.social;

import com.physicalchange.exception.NotFoundException;

/** Las 8 redes del generador de contenido (orden del contrato, docs/api-contenido.md). */
public enum SocialNetwork {
    INSTAGRAM("Instagram"), TIKTOK("TikTok"), YOUTUBE("YouTube"), LINKEDIN("LinkedIn"), X("X"), FACEBOOK("Facebook"),
    THREADS("Threads"), PINTEREST("Pinterest");

    private final String label;
    SocialNetwork(String label) { this.label = label; }
    public String label() { return label; }

    /** Red de una ruta (/accounts/{network}): una desconocida responde 404. */
    public static SocialNetwork fromPath(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException e) { throw new NotFoundException("Red no encontrada"); }
    }
}
