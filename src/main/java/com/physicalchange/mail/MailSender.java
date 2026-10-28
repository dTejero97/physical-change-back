package com.physicalchange.mail;

/**
 * Proveedor de correo (T137). La lógica de negocio solo conoce esta interfaz: cambiar de Resend a otro proveedor
 * es añadir otra implementación y elegirla con MAIL_PROVIDER, sin tocar los servicios.
 */
public interface MailSender {
    void send(MailMessage message);
}
