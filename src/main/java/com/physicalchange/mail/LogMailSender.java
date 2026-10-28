package com.physicalchange.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Modo de pruebas (MAIL_PROVIDER=log, por defecto): escribe el correo en el log y no envía nada. */
public class LogMailSender implements MailSender {
    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    @Override
    public void send(MailMessage m) {
        log.info("[correo de prueba, no enviado] Para: {} · Asunto: {}\n{}", m.to(), m.subject(), m.text());
    }
}
