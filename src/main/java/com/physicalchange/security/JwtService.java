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

    public String generate(AuthUser u) {
        Date now = new Date();
        return Jwts.builder().subject(String.valueOf(u.id())).claim("email", u.email()).claim("role", u.role().name())
                .issuedAt(now).expiration(new Date(now.getTime() + expirationMs)).signWith(key).compact();
    }

    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthUser(Long.valueOf(c.getSubject()), c.get("email", String.class), Role.valueOf(c.get("role", String.class)));
    }
}
