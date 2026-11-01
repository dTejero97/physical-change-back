package com.physicalchange.service.social;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Cifrado en reposo de tokens y code_verifier (ADR-014 §4): AES-256-GCM (JCA), IV aleatorio de 12 bytes, etiqueta de 128 bits.
 * Formato: v1.&lt;keyId&gt;.&lt;base64url(iv ‖ cifrado ‖ tag)&gt;, keyId = 8 primeros hex del SHA-256 de la clave.
 * El AAD ata el valor a su fila y campo: un valor copiado a otra fila u otro campo no se descifra.
 * Sin SOCIAL_TOKEN_KEY, available() es false y todas las redes quedan NOT_CONFIGURED. La clave nunca se escribe en logs.
 */
@Component
public class TokenCipher {
    private static final int IV_BYTES = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Key current;
    private final Key previous;

    public TokenCipher(@Value("${app.social.token-key:}") String key, @Value("${app.social.token-key-previous:}") String previousKey) {
        this.current = parse(key, "SOCIAL_TOKEN_KEY");
        this.previous = parse(previousKey, "SOCIAL_TOKEN_KEY_PREVIOUS");
    }

    public boolean available() { return current != null; }

    public String encrypt(String plain, String aad) {
        if (plain == null) return null;
        if (current == null) throw new IllegalStateException("SOCIAL_TOKEN_KEY no está configurada");
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, current.spec, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v1." + current.id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar");
        }
    }

    /** Descifra con la clave que indique el keyId (actual o anterior). DecryptException si no se puede (clave perdida, AAD distinto…). */
    public String decrypt(String value, String aad) {
        if (value == null) return null;
        String[] parts = value.split("\\.", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new DecryptException();
        Key k = current != null && current.id.equals(parts[1]) ? current : previous != null && previous.id.equals(parts[1]) ? previous : null;
        if (k == null) throw new DecryptException();
        try {
            byte[] in = Base64.getUrlDecoder().decode(parts[2]);
            if (in.length <= IV_BYTES) throw new DecryptException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k.spec, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DecryptException();
        }
    }

    /** El valor está cifrado con una clave que no es la actual (rotación): se vuelve a cifrar al usarlo. */
    public boolean needsReencrypt(String value) {
        return value != null && current != null && !value.startsWith("v1." + current.id + ".");
    }

    /** AAD de un campo de social_account. */
    public static String accountAad(Long ownerId, String network, String field) { return "social_account:" + ownerId + ":" + network + ":" + field; }
    /** AAD del code_verifier de un state (atado a su hash). */
    public static String stateAad(String stateHash) { return "social_oauth_state:" + stateHash + ":code_verifier"; }

    /** No se puede descifrar: sin detalles (no da pistas ni filtra datos). */
    public static class DecryptException extends RuntimeException {
        public DecryptException() { super("No se puede descifrar el valor"); }
    }

    private record Key(String id, SecretKeySpec spec) {}

    private static Key parse(String base64, String variable) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] raw;
        try { raw = Base64.getDecoder().decode(base64.strip()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException(variable + " no es base64 válido"); }
        if (raw.length != 32) throw new IllegalStateException(variable + " debe tener 32 bytes (openssl rand -base64 32)");
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 8);
            return new Key(id, new SecretKeySpec(raw, "AES"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 no disponible");
        }
    }
}
