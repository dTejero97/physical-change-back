package com.physicalchange.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

/**
 * Correos de la plataforma (T137): invitación, recuperación de contraseña, verificación de cuenta y avisos.
 * - Se envían cuando la transacción se confirma (si se deshace, no sale ningún correo).
 * - Un fallo del proveedor se registra y no rompe la operación (el enlace sigue disponible en la app).
 * - Todo texto que viene del usuario (nombres) se escapa en el HTML.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String BRAND = "Physical Change";

    private final MailSender sender;
    private final String publicUrl, support;

    public MailService(MailSender sender, @Value("${app.public-url}") String publicUrl, @Value("${app.mail.support}") String support) {
        this.sender = sender;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.support = support;
    }

    public void invitation(String to, String name, String trainerName, String token, int validDays) {
        String link = publicUrl + "/invitacion/" + token;
        send(to, trainerName + " te invita a " + BRAND,
                "Hola " + name + ",",
                trainerName + " te ha invitado a entrenar con " + BRAND + ". Crea tu cuenta desde este enlace (válido " + validDays + " días):",
                "Crear mi cuenta", link);
    }

    /** Invitación a unirse al equipo de un profesional como colaborador (ADR-011). */
    public void teamInvitation(String to, String name, String ownerName, String token, int validDays) {
        String link = publicUrl + "/equipo/invitacion/" + token;
        send(to, ownerName + " te invita a su equipo en " + BRAND,
                "Hola " + name + ",",
                ownerName + " te ha añadido como colaborador de su equipo en " + BRAND + ". Elige tu contraseña para empezar (el enlace vale " + validDays + " días):",
                "Unirme al equipo", link);
    }

    public void passwordReset(String to, String name, String token, int validMinutes) {
        String link = publicUrl + "/restablecer/" + token;
        send(to, "Restablece tu contraseña de " + BRAND,
                "Hola " + name + ",",
                "Hemos recibido una petición para cambiar tu contraseña. El enlace vale " + validMinutes
                        + " minutos y un solo uso. Si no has sido tú, ignora este correo: tu contraseña no cambia.",
                "Elegir una contraseña nueva", link);
    }

    public void verifyEmail(String to, String name, String token, int validHours) {
        String link = publicUrl + "/verificar/" + token;
        send(to, "Confirma tu email en " + BRAND,
                "Hola " + name + ",",
                "Confirma tu dirección de correo para activar tu cuenta. El enlace vale " + validHours + " horas.",
                "Confirmar mi email", link);
    }

    /** Aviso genérico sin botón. */
    public void notification(String to, String name, String subject, String message) {
        send(to, subject, "Hola " + name + ",", message, null, null);
    }

    private void send(String to, String subject, String greeting, String body, String action, String link) {
        String text = greeting + "\n\n" + body + (link == null ? "" : "\n\n" + link)
                + "\n\n¿Dudas? Escríbenos a " + support + "\n— " + BRAND;
        String html = "<div style=\"font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#1b1f26\">"
                + "<h2 style=\"color:#ff6a2b\">" + BRAND + "</h2>"
                + "<p>" + esc(greeting) + "</p><p>" + esc(body) + "</p>"
                + (link == null ? "" : "<p><a href=\"" + esc(link) + "\" style=\"display:inline-block;background:#ff6a2b;color:#fff;"
                    + "padding:12px 20px;border-radius:8px;text-decoration:none\">" + esc(action) + "</a></p>"
                    + "<p style=\"font-size:12px;color:#666\">Si el botón no funciona, copia este enlace: " + esc(link) + "</p>")
                + "<hr style=\"border:0;border-top:1px solid #eee\"><p style=\"font-size:12px;color:#666\">¿Dudas? Escríbenos a "
                + esc(support) + "</p></div>";
        MailMessage m = new MailMessage(to, subject.replaceAll("[\\r\\n]+", " "), html, text);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deliver(m); }
            });
        } else deliver(m);
    }

    private void deliver(MailMessage m) {
        try { sender.send(m); }
        catch (RuntimeException e) { log.error("No se ha podido enviar el correo '{}': {}", m.subject(), e.getMessage()); }
    }

    private static String esc(String s) { return HtmlUtils.htmlEscape(s == null ? "" : s); }
}
