package com.physicalchange.payment;

import com.physicalchange.payment.PaymentProvider.InvalidSignatureException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Firma de los webhooks de Stripe (ADR-009 §4): cabecera {@code Stripe-Signature: t=<segundos>,v1=<hex>[,v1=…]}.
 * Firma esperada = HMAC-SHA256 con el secreto del endpoint (whsec_…, tal cual) sobre {@code "<t>." + cuerpo en bruto}.
 * Se acepta si coincide algún v1 (rotación de secretos) y t no se aleja más de la tolerancia. Comparación en tiempo constante.
 */
public final class StripeSignature {
    public static final long TOLERANCE_SECONDS = 300;

    private StripeSignature() {}

    public static void verify(byte[] payload, String header, String secret, Clock clock) {
        if (header == null || header.isBlank() || secret == null || secret.isBlank()) throw new InvalidSignatureException("Sin firma");
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim(), value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                try { timestamp = Long.parseLong(value); } catch (NumberFormatException e) { throw new InvalidSignatureException("Marca de tiempo no válida"); }
            } else if ("v1".equals(key)) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) throw new InvalidSignatureException("Firma mal formada");
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > TOLERANCE_SECONDS) throw new InvalidSignatureException("Firma caducada");
        byte[] expected = HexFormat.of().formatHex(hmac(secret, timestamp, payload)).getBytes(StandardCharsets.US_ASCII);
        boolean ok = false;
        for (String s : signatures) ok |= MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII));
        if (!ok) throw new InvalidSignatureException("Firma incorrecta");
    }

    /** Cabecera válida para un cuerpo (la usan los tests para simular a Stripe). */
    public static String sign(byte[] payload, String secret, long timestamp) {
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac(secret, timestamp, payload));
    }

    private static byte[] hmac(String secret, long timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
