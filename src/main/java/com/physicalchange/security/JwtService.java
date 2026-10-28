package com.physicalchange.security;

import com.physicalchange.domain.Role;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes}") long minutes) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("JWT_SECRET debe tener >= 32 caracteres");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = minutes * 60_000;
    }

    public String generate(AuthUser u) { return generate(u, 0); }

    /** tv = token_version del usuario: si cambia, el token deja de valer (T141). */
    public String generate(AuthUser u, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder().subject(String.valueOf(u.id())).claim("email", u.email()).claim("role", u.role().name())
                .claim("tv", tokenVersion).issuedAt(now).expiration(new Date(now.getTime() + expirationMs)).signWith(key).compact();
    }

    public AuthUser parse(String token) { return parseWithVersion(token).user(); }

    public Parsed parseWithVersion(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Integer tv = c.get("tv", Integer.class);
        return new Parsed(new AuthUser(Long.valueOf(c.getSubject()), c.get("email", String.class), Role.valueOf(c.get("role", String.class))),
                tv == null ? 0 : tv);
    }

    public record Parsed(AuthUser user, int tokenVersion) {}
}
