package com.physicalchange.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.physicalchange.exception.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * Cloudflare Workers AI (ADR-003): POST https://api.cloudflare.com/client/v4/accounts/{cuenta}/ai/run/{modelo} con
 * { messages, max_tokens, response_format? }. Respuesta: result.response (texto; objeto en JSON mode) y result.usage
 * { prompt_tokens, completion_tokens }. Conexión 5 s, lectura 60 s y un reintento ante 429, 5xx o timeout de conexión.
 * Nunca se registran prompts, respuestas ni el token.
 */
public class WorkersAiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(WorkersAiProvider.class);
    private static final String DOWN = "Physical AI no responde ahora mismo. No se ha gastado ningún crédito";

    private final String accountId;
    private final String token;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WorkersAiProvider(String accountId, String token, String model, ObjectMapper json) {
        this.accountId = accountId; this.token = token; this.model = model; this.json = json;
    }

    @Override public boolean enabled() { return true; }
    @Override public String name() { return "workers-ai"; }
    @Override public String model() { return model; }

    @Override
    public Completion complete(String system, List<Turn> messages, String jsonSchema, int maxTokens) {
        ObjectNode body = json.createObjectNode();
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        for (Turn t : messages) msgs.addObject().put("role", t.role()).put("content", t.content());
        body.put("max_tokens", maxTokens);
        try {
            if (jsonSchema != null) body.putObject("response_format").put("type", "json_schema").set("json_schema", json.readTree(jsonSchema));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.cloudflare.com/client/v4/accounts/" + accountId + "/ai/run/" + model))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = send(req, true);
            JsonNode result = json.readTree(res.body()).path("result");
            JsonNode response = result.path("response");
            String text = response.isTextual() ? response.asText() : response.isMissingNode() || response.isNull() ? "" : json.writeValueAsString(response);
            JsonNode usage = result.path("usage");
            String prompt = system + messages.stream().map(Turn::content).reduce("", String::concat);
            int in = usage.path("prompt_tokens").asInt(prompt.length() / 4);
            int out = usage.path("completion_tokens").asInt(text.length() / 4);
            return new Completion(text, in, out);
        } catch (IOException e) {
            log.warn("Workers AI: respuesta ilegible o error de E/S ({})", e.getClass().getSimpleName());
            throw new AiException(503, DOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(503, DOWN);
        }
    }

    private HttpResponse<String> send(HttpRequest req, boolean retry) throws IOException, InterruptedException {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 200) return res;
            if (retry && (s == 429 || s >= 500)) { Thread.sleep(1500); return send(req, false); }
            log.warn("Workers AI respondió {}", s);
            throw new AiException(503, DOWN);
        } catch (HttpConnectTimeoutException e) {
            if (retry) { Thread.sleep(1500); return send(req, false); }
            throw new AiException(503, DOWN);
        } catch (HttpTimeoutException e) {
            log.warn("Workers AI: tiempo de espera agotado");
            throw new AiException(503, DOWN);
        }
    }
}
