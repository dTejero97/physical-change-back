package com.physicalchange.mail;

/** Correo ya montado: destinatario, asunto, HTML y texto plano. */
public record MailMessage(String to, String subject, String html, String text) {}
