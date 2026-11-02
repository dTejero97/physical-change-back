package com.physicalchange.config;

import com.physicalchange.service.BackgroundService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/**
 * Fondos iniciales de la página de carga (T208): en PostgreSQL los siembra V18; aquí solo se completan si faltan (tests con H2)
 * y se generan sus variantes en segundo plano para que la primera visita no espere.
 */
@Component
public class BackgroundSeedInitializer implements ApplicationRunner {
    private final BackgroundService backgrounds;
    public BackgroundSeedInitializer(BackgroundService b) { backgrounds = b; }

    @Override public void run(ApplicationArguments args) {
        backgrounds.ensureSeeded();
        CompletableFuture.runAsync(backgrounds::warmUp);
    }
}
