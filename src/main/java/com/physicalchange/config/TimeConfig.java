package com.physicalchange.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Reloj de la plataforma en la zona única de ADR-008 (app.timezone, Europe/Madrid por defecto): "hoy" y "ahora" no dependen
 * de la zona del servidor (UTC en Render). Los tests pueden sustituirlo por un reloj fijo con un bean @Primary.
 */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock(@Value("${app.timezone:Europe/Madrid}") String zone) { return Clock.system(ZoneId.of(zone)); }
}
