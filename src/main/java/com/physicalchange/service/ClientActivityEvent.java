package com.physicalchange.service;

/**
 * Ha cambiado la actividad registrada de un cliente (series, workout completado, peso, asistencia).
 * Se procesa tras confirmar la transacción para actualizar estadísticas y logros (ADR-012 §5).
 * Editar la ficha o el perfil no lo publica.
 */
public record ClientActivityEvent(Long clientId) {}
