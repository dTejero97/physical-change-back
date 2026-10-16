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
    private final String resetPassword;

    public AdminSeedInitializer(UserRepository users, PasswordEncoder encoder,
                                @Value("${app.admin.email}") String adminEmail,
                                @Value("${app.admin.password}") String adminPassword,
                                @Value("${app.admin.reset-password:}") String resetPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail.toLowerCase();
        this.adminPassword = adminPassword;
        this.resetPassword = resetPassword;
    }

    /**
     * Crea el admin si no existe. Si ADMIN_RESET_PASSWORD está definida, fija esa contraseña
     * en cada arranque: bórrala de Render cuando ya hayas entrado.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var existing = users.findByEmailIgnoreCase(adminEmail);
        if (existing.isPresent()) {
            if (resetPassword != null && !resetPassword.isBlank()) {
                AppUser admin = existing.get();
                admin.setPasswordHash(encoder.encode(resetPassword));
                users.save(admin);
                log.warn("Contraseña del administrador '{}' restablecida con ADMIN_RESET_PASSWORD. Quita esa variable de Render.", adminEmail);
            }
            return;
        }
        AppUser admin = new AppUser();
        admin.setEmail(adminEmail);
        admin.setFullName("Administrador");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(adminPassword));
        users.save(admin);
        log.warn("Usuario administrador creado: '{}'. Cambia la contraseña en Configuración.", adminEmail);
    }
}
