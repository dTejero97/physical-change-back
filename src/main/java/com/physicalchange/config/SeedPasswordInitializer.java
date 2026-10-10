package com.physicalchange.config;

import com.physicalchange.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Los scripts SQL no contienen contraseñas. Los usuarios de prueba se cargan con el
 * marcador PENDING_PASSWORD y, si SEED_PASSWORD está definida, se hashea con BCrypt al arrancar.
 */
@Component
public class SeedPasswordInitializer implements ApplicationRunner {
    private final UserRepository users; private final PasswordEncoder enc; private final String seed;
    public SeedPasswordInitializer(UserRepository u, PasswordEncoder e, @Value("${app.seed-password:}") String s) {
        this.users = u; this.enc = e; this.seed = s;
    }
    @Override public void run(ApplicationArguments args) {
        if (seed == null || seed.isBlank()) return;
        users.findByPasswordHash("PENDING_PASSWORD").forEach(u -> { u.setPasswordHash(enc.encode(seed)); users.save(u); });
    }
}
