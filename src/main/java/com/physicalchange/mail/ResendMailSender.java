package com.physicalchange.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envío con Resend (POST https://api.resend.com/emails, Authorization: Bearer). Campos según su documentación:
 * from, to, subject, html, text y headers; Idempotency-Key evita duplicados si se reintenta.
 * La dirección de soporte va como cabecera Reply-To (el nombre del campo propio de Resend no está confirmado).
 * Ni la clave ni el contenido del correo se escriben en el log.
 */
public class ResendMailSender implements MailSender {
    static final String ENDPOINT = "https://api.resend.com/emails";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String apiKey, from, replyTo;

    public ResendMailSender(ObjectMapper json, String apiKey, String from, String replyTo) {
        this.json = json; this.apiKey = apiKey; this.from = from; this.replyTo = replyTo;
    }

    @Override
    public void send(MailMessage m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(m.to()));
        body.put("subject", m.subject());
        body.put("html", m.html());
        body.put("text", m.text());
        if (replyTo != null && !replyTo.isBlank()) body.put("headers", Map.of("Reply-To", replyTo));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("Resend respondió " + res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envío de correo interrumpido", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se ha podido contactar con Resend", e);
        }
    }
}
