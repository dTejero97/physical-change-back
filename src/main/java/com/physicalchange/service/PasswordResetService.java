package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.mail.MailService;
import com.physicalchange.repository.*;
import com.physicalchange.security.RateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Recuperación de contraseña por email (T137).
 * - forgot: siempre responde igual, exista o no la cuenta (no revela qué emails están registrados);
 *   máximo 3 correos por email y hora.
 * - reset: enlace de un solo uso válido 60 min; cambiar la contraseña cierra todas las sesiones (T141).
 */
@Service
public class PasswordResetService {
    static final int VALID_MINUTES = 60;
    static final int MAX_PER_HOUR = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final PasswordEncoder enc;
    private final MailService mail;
    private final RateLimiter perEmail = new RateLimiter();

    public PasswordResetService(UserRepository u, UserTokenRepository t, PasswordEncoder e, MailService m) {
        users = u; tokens = t; enc = e; mail = m;
    }

    @Transactional
    public void forgot(String email) {
        String key = email.trim().toLowerCase();
        if (perEmail.hit(key, MAX_PER_HOUR, 3_600_000L) > 0) return;
        users.findByEmailIgnoreCase(key).filter(AppUser::isActive).ifPresent(u -> {
            tokens.invalidate(u.getId(), "RESET_PASSWORD");
            String token = newToken();
            tokens.save(new UserToken(u.getId(), "RESET_PASSWORD", sha256(token), LocalDateTime.now().plusMinutes(VALID_MINUTES)));
            mail.passwordReset(u.getEmail(), u.getFullName(), token, VALID_MINUTES);
        });
    }

    @Transactional
    public void reset(String token, String newPassword) {
        UserToken t = tokens.findByTokenHash(sha256(token)).filter(x -> "RESET_PASSWORD".equals(x.getPurpose()) && x.usable())
                .orElseThrow(() -> new BadRequestException("El enlace no es válido o ha caducado. Pide uno nuevo."));
        AppUser u = users.findById(t.getUserId()).filter(AppUser::isActive)
                .orElseThrow(() -> new BadRequestException("El enlace no es válido o ha caducado. Pide uno nuevo."));
        u.setPasswordHash(enc.encode(newPassword));
        t.markUsed();
    }

    /** 32 bytes aleatorios en base64url (256 bits). */
    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
