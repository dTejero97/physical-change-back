package com.physicalchange.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import java.util.TimeZone;

/**
 * T213 (ADR-008): la zona por defecto de la JVM pasa a ser la de la plataforma (app.timezone, Europe/Madrid) antes de crear
 * ningún bean. Render corre en UTC: sin esto, los LocalDate.now() y LocalDateTime.now() que no usan el Clock de TimeConfig
 * (fechas de recibos, logros, createdAt...) calculaban otro día entre las 23:00 y las 00:00 y guardaban horas desfasadas.
 * El Clock sigue siendo la forma de pedir "hoy" en el código nuevo (los tests pueden fijarlo).
 */
public class TimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        TimeZone.setDefault(TimeZone.getTimeZone(java.time.ZoneId.of(env.getProperty("app.timezone", "Europe/Madrid"))));
    }
}
