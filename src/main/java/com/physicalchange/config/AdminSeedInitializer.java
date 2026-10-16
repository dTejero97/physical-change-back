package com.physicalchange.config;

import com.physicalchange.domain.AppUser;
import com.physicalchange.domain.Role;
import com.physicalchange.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el usuario administrador la primera vez que arranca la aplicación.
 * Si ya existe, no toca su contraseña (cambiarla después desde Configuración).
 */
@Component
public class AdminSeedInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeedInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminSeedInitializer(UserRepository users, PasswordEncoder encoder,
                                @Value("${app.admin.email}") String adminEmail,
                                @Value("${app.admin.password}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail.toLowerCase();
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findByEmailIgnoreCase(adminEmail).isPresent()) return;
        AppUser admin = new AppUser();
        admin.setEmail(adminEmail);
        admin.setFullName("Administrador");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(adminPassword));
        users.save(admin);
        log.warn("Usuario administrador creado: '{}'. Cambia la contraseña en Configuración.", adminEmail);
    }
}
