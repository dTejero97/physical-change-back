package com.physicalchange.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tareas programadas (ADR-014 §6): publicación programada cada minuto y sincronización de métricas cada hora.
 * CONTENT_SCHEDULER_ENABLED=false las apaga (en los tests el job se invoca a mano).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.content.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
