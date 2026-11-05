package com.physicalchange.service;

/**
 * Una suscripción acaba de empezar, por pago con Stripe o por alta manual (SubscriptionService.start). Lo atiende
 * ProgramAssignmentService después del commit: asigna el programa del plan y avisa al profesional (T250).
 */
public record SubscriptionStartedEvent(Long subscriptionId) {}
