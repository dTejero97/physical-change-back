package com.physicalchange.service.audit;

/** Códigos del registro de auditoría (ADR-016 §3, docs/api-auditoria.md). El código se guarda tal cual: no renombrar. */
public enum AuditAction {
    LOGIN_OK("Inicio de sesión", "Sesión"),
    LOGIN_FAILED("Inicio de sesión fallido", "Sesión"),
    LOGIN_LOCKED("Acceso bloqueado por intentos", "Sesión"),
    LOGOUT("Cierre de sesión", "Sesión"),
    PASSWORD_CHANGED("Contraseña cambiada", "Sesión"),
    PASSWORD_RESET("Contraseña restablecida", "Sesión"),

    PROFESSIONAL_CREATED("Profesional dado de alta", "Personas"),
    PROFESSIONAL_UPDATED("Profesional modificado", "Personas"),
    PROFESSIONAL_PASSWORD_SET("Contraseña de profesional cambiada", "Personas"),
    CLIENT_CREATED("Cliente dado de alta", "Personas"),
    CLIENT_UPDATED("Cliente modificado", "Personas"),
    NUTRITIONIST_ASSIGNED("Responsable de nutrición asignado", "Personas"),
    TEAM_INVITED("Colaborador invitado", "Personas"),
    TEAM_PERMISSIONS_CHANGED("Permisos de colaborador cambiados", "Personas"),
    TEAM_REMOVED("Colaborador quitado", "Personas"),

    RECEIPT_CREATED("Comprobante creado", "Dinero"),
    RECEIPT_PAID("Comprobante pagado", "Dinero"),
    RECEIPT_VOIDED("Comprobante anulado", "Dinero"),
    STRIPE_PAYMENT_CONFIRMED("Pago con tarjeta confirmado", "Dinero"),
    STRIPE_REFUND("Reembolso en Stripe", "Dinero"),
    STRIPE_ACCOUNT_LINKED("Cuenta de Stripe vinculada", "Dinero"),
    STRIPE_ACCOUNT_UNLINKED("Cuenta de Stripe desvinculada", "Dinero"),
    SUBSCRIPTION_STARTED("Suscripción iniciada", "Dinero"),
    SUBSCRIPTION_CANCELLED("Suscripción cancelada", "Dinero"),

    NUTRITION_PLAN_ASSIGNED("Plan de nutrición asignado", "Planes"),
    NUTRITION_PLAN_CHANGED("Plan de nutrición cambiado", "Planes"),
    ROUTINE_ASSIGNED("Rutina asignada", "Planes"),
    CHANGE_REQUEST_DECIDED("Solicitud de cambio resuelta", "Planes"),

    HEALTH_DATA_READ("Datos de salud consultados", "Salud"),

    EXPORT("Exportación", "Datos"),
    SETTINGS_CHANGED("Configuración cambiada", "Datos");

    private final String label, group;
    AuditAction(String label, String group) { this.label = label; this.group = group; }
    public String label() { return label; }
    public String group() { return group; }
}
