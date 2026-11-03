package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.content.ContentPublishJob;
import com.physicalchange.service.social.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Infraestructura de conectores (ADR-014 §1-§6) con un conector falso para Instagram (nunca se llama a la red) y una
 * SOCIAL_TOKEN_KEY generada al arrancar el test (nunca hay una clave en el repositorio): OAuth con state y PKCE,
 * tokens cifrados, estados de cuenta y publicación automática como mucho una vez.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SocialConnectorFlowTest {

    static String randomKey() { byte[] k = new byte[32]; new SecureRandom().nextBytes(k); return Base64.getEncoder().encodeToString(k); }

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) { r.add("app.social.token-key", SocialConnectorFlowTest::randomKey); }

    /** Instagram "configurado" que publica reels, con PKCE. Guarda lo que recibe para comprobarlo. */
    static class FakeSocialConnector implements SocialConnector {
        final List<String> verifiers = new CopyOnWriteArrayList<>();
        final List<PublishRequest> published = new CopyOnWriteArrayList<>();
        final List<String> revoked = new CopyOnWriteArrayList<>();
        volatile RuntimeException publishError;
        volatile boolean exchangeFails;
        @Override public SocialNetwork network() { return SocialNetwork.INSTAGRAM; }
        @Override public boolean configured() { return true; }
        @Override public Capabilities capabilities() { return new Capabilities(true, true, Set.of("REEL"), false, false, true); }
        @Override public String authorizationUrl(String state, String challenge, String redirectUri) {
            return UriComponentsBuilder.fromHttpUrl("https://auth.fake.test/authorize").queryParam("state", state)
                    .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256").queryParam("redirect_uri", redirectUri)
                    .build().toUriString();
        }
        @Override public TokenSet exchangeCode(String code, String verifier, String redirectUri) {
            if (exchangeFails) throw new ConnectorException(ConnectorException.Kind.REJECTED, "invalid_grant");
            verifiers.add(verifier);
            return new TokenSet("fake-access-" + code, "fake-refresh-" + code, Instant.now().plusSeconds(3600), "publish", "ext-1", "@coach");
        }
        @Override public TokenSet refresh(TokenSet current) { throw new ConnectorException(ConnectorException.Kind.EXPIRED, "expired"); }
        @Override public void revoke(TokenSet tokens) { revoked.add(tokens.accessToken()); }
        @Override public PublishResult publish(TokenSet tokens, PublishRequest post) {
            if (publishError != null) throw publishError;
            published.add(post);
            return new PublishResult("media-" + post.targetId(), "https://instagram.fake.test/p/" + post.targetId());
        }
        @Override public List<MetricSnapshot> fetchMetrics(TokenSet tokens, List<String> ids) { return List.of(); }
    }

    @TestConfiguration
    static class Config { @Bean FakeSocialConnector fakeInstagram() { return new FakeSocialConnector(); } }

    @Autowired MockMvc mvc;
    @Autowired FakeSocialConnector fake;
    @Autowired UserRepository users;
    @Autowired TeamMemberRepository team;
    @Autowired SocialAccountRepository accounts;
    @Autowired SocialOauthStateRepository states;
    @Autowired ContentPostRepository posts;
    @Autowired NotificationRepository notifications;
    @Autowired ContentPublishJob job;
    @Autowired TokenCipher cipher;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx, trainer, other, collab;
    private AppUser trainerUser, otherUser;

    @BeforeEach void data() {
        fake.verifiers.clear(); fake.published.clear(); fake.revoked.clear(); fake.publishError = null; fake.exchangeFails = false;
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerUser = save("t-" + sfx + "@test", Role.TRAINER, null);
        otherUser = save("o-" + sfx + "@test", Role.TRAINER, null);
        trainer = token(trainerUser); other = token(otherUser);
        AppUser c = save("col-" + sfx + "@test", Role.COLLABORATOR, trainerUser.getId());
        TeamMember m = new TeamMember(c.getId(), trainerUser.getId()); m.activate(); m.setCanUseContent(true); team.save(m);
        collab = token(c);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()); }

    private MvcResult raw(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        return mvc.perform(req).andExpect(status().is(status)).andReturn();
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        String text = raw(method, url, auth, body, status).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private Map<String, String> query(String url) {
        Map<String, String> m = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach((k, v) -> m.put(k, v.get(0)));
        return m;
    }

    private String callback(String state, String code) { return "{\"network\":\"INSTAGRAM\",\"state\":\"" + state + "\",\"code\":\"" + code + "\"}"; }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private void connect(String auth, String code) throws Exception {
        String state = query(send("POST", "/api/content/accounts/INSTAGRAM/connect", auth, null, 200).get("authorizationUrl").asText()).get("state");
        send("POST", "/api/content/oauth/callback", auth, callback(state, code), 200);
    }

    // ---------- OAuth ----------

    @Test void oauthConStatePkceYUnSoloUso() throws Exception {
        JsonNode list = send("GET", "/api/content/accounts", trainer, null, 200);
        assertEquals("DISCONNECTED", list.get(0).get("status").asText());
        assertTrue(list.get(0).get("capabilities").get("publish").asBoolean());
        assertEquals("NOT_CONFIGURED", list.get(1).get("status").asText());   // TikTok sigue sin conector

        JsonNode c = send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 200);
        Map<String, String> q = query(c.get("authorizationUrl").asText());
        String state = q.get("state");
        assertTrue(state.length() >= 43);
        assertEquals("http://localhost:4200/marketing/contenido/cuentas/oauth", q.get("redirect_uri"));   // fija, de la configuración
        assertTrue(OffsetDateTime.parse(c.get("expiresAt").asText()).isBefore(OffsetDateTime.now().plusMinutes(11)));
        // Solo se guarda el hash del state; el code_verifier va cifrado.
        assertTrue(states.findAll().stream().noneMatch(s -> s.getStateHash().equals(state)));
        SocialOauthState saved = states.findByStateHash(sha256Hex(state)).orElseThrow();
        assertTrue(saved.getCodeVerifierEnc().startsWith("v1."));

        send("POST", "/api/content/oauth/callback", trainer, callback("inventado", "abc"), 400);
        // Otro profesional con el state de este: 400 y el state queda consumido.
        JsonNode bad = send("POST", "/api/content/oauth/callback", other, callback(state, "abc"), 400);
        assertEquals("La conexión ha caducado o no es válida; vuelve a intentarlo", bad.get("message").asText());
        send("POST", "/api/content/oauth/callback", trainer, callback(state, "abc"), 400);
        assertNull(states.findByStateHash(sha256Hex(state)).orElseThrow().getCodeVerifierEnc());

        // Flujo correcto: el verifier que llega a la red corresponde al code_challenge (S256).
        q = query(send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 200).get("authorizationUrl").asText());
        MvcResult ok = raw("POST", "/api/content/oauth/callback", trainer, callback(q.get("state"), "c0de"), 200);
        String body = ok.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("fake-access") || body.contains("fake-refresh"), "los tokens nunca salen en la API");
        JsonNode acc = json.readTree(body);
        assertEquals("CONNECTED", acc.get("status").asText());
        assertEquals("@coach", acc.get("externalUsername").asText());
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(fake.verifiers.get(0).getBytes(StandardCharsets.US_ASCII)));
        assertEquals(q.get("code_challenge"), challenge);
        send("POST", "/api/content/oauth/callback", trainer, callback(q.get("state"), "c0de"), 400);   // reutilizar: no
        send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 409);                   // ya vinculada

        // Tokens cifrados en la base, atados a su fila y campo.
        SocialAccount a = accounts.findByOwnerIdAndNetwork(trainerUser.getId(), "INSTAGRAM").orElseThrow();
        assertTrue(a.getAccessTokenEnc().startsWith("v1.") && !a.getAccessTokenEnc().contains("fake-access"));
        assertEquals("fake-access-c0de", cipher.decrypt(a.getAccessTokenEnc(), TokenCipher.accountAad(a.getOwnerId(), "INSTAGRAM", "access_token")));
        assertThrows(TokenCipher.DecryptException.class,
                () -> cipher.decrypt(a.getAccessTokenEnc(), TokenCipher.accountAad(otherUser.getId(), "INSTAGRAM", "access_token")));
        assertThrows(TokenCipher.DecryptException.class,
                () -> cipher.decrypt(a.getAccessTokenEnc(), TokenCipher.accountAad(a.getOwnerId(), "INSTAGRAM", "refresh_token")));
        assertFalse(send("GET", "/api/content/accounts", collab, null, 200).toString().contains("fake-"));
        // El otro profesional no ve la cuenta de este.
        assertEquals("DISCONNECTED", send("GET", "/api/content/accounts", other, null, 200).get(0).get("status").asText());
    }

    @Test void stateCaducadoCanceladoOCanjeRechazado() throws Exception {
        String expired = "caducado-" + sfx;
        states.save(new SocialOauthState(sha256Hex(expired), trainerUser.getId(), trainerUser.getId(), "INSTAGRAM", null,
                Instant.now().minusSeconds(1)));
        send("POST", "/api/content/oauth/callback", trainer, callback(expired, "abc"), 400);

        String state = query(send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 200).get("authorizationUrl").asText()).get("state");
        JsonNode cancelled = send("POST", "/api/content/oauth/callback", trainer,
                "{\"network\":\"INSTAGRAM\",\"state\":\"" + state + "\",\"error\":\"access_denied\"}", 400);
        assertEquals("Conexión cancelada", cancelled.get("message").asText());
        send("POST", "/api/content/oauth/callback", trainer, callback(state, "abc"), 400);

        fake.exchangeFails = true;
        state = query(send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 200).get("authorizationUrl").asText()).get("state");
        JsonNode gw = send("POST", "/api/content/oauth/callback", trainer, callback(state, "abc"), 502);
        assertEquals("La red no ha aceptado la conexión", gw.get("message").asText());
        assertTrue(accounts.findByOwnerIdAndNetwork(trainerUser.getId(), "INSTAGRAM").isEmpty());
        // Network distinta de la del state.
        fake.exchangeFails = false;
        state = query(send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 200).get("authorizationUrl").asText()).get("state");
        send("POST", "/api/content/oauth/callback", trainer, "{\"network\":\"TIKTOK\",\"state\":\"" + state + "\",\"code\":\"x\"}", 400);
    }

    // ---------- Estados de la cuenta ----------

    @Test void desactivarReactivarYDesconectar() throws Exception {
        connect(trainer, "k1");
        send("POST", "/api/content/accounts/INSTAGRAM/enable", trainer, null, 409);
        assertEquals("DISABLED", send("POST", "/api/content/accounts/INSTAGRAM/disable", trainer, null, 200).get("status").asText());
        send("POST", "/api/content/accounts/INSTAGRAM/disable", trainer, null, 409);
        send("POST", "/api/content/accounts/INSTAGRAM/disable", collab, null, 403);
        send("POST", "/api/content/accounts/INSTAGRAM/disable", other, null, 404);
        assertEquals("CONNECTED", send("POST", "/api/content/accounts/INSTAGRAM/enable", trainer, null, 200).get("status").asText());
        send("POST", "/api/content/accounts/INSTAGRAM/sync", trainer, null, 409);   // este conector no da métricas

        // Token caducado al reactivar y la red no lo refresca → EXPIRED con un aviso.
        SocialAccount a = accounts.findByOwnerIdAndNetwork(trainerUser.getId(), "INSTAGRAM").orElseThrow();
        a.setTokenExpiresAt(Instant.now().minusSeconds(60));
        a.setStatus("DISABLED");
        accounts.save(a);
        JsonNode exp = send("POST", "/api/content/accounts/INSTAGRAM/enable", trainer, null, 200);
        assertEquals("EXPIRED", exp.get("status").asText());
        assertNotNull(exp.get("lastError").asText());
        assertEquals(1, notifications.countByUserIdAndType(trainerUser.getId(), "SOCIAL_ACCOUNT_ATTENTION"));

        send("DELETE", "/api/content/accounts/INSTAGRAM", collab, null, 403);
        send("DELETE", "/api/content/accounts/INSTAGRAM", trainer, null, 204);
        assertEquals("fake-access-k1", fake.revoked.get(0));
        assertTrue(accounts.findByOwnerIdAndNetwork(trainerUser.getId(), "INSTAGRAM").isEmpty());
        assertEquals("DISCONNECTED", send("GET", "/api/content/accounts", trainer, null, 200).get(0).get("status").asText());
        send("DELETE", "/api/content/accounts/INSTAGRAM", trainer, null, 404);
    }

    // ---------- Publicación automática ----------

    private long scheduledPost(String format) throws Exception {
        long id = send("POST", "/api/content/posts", trainer, "{\"title\":\"Auto " + sfx + "\",\"format\":\"" + format
                + "\",\"networks\":[\"INSTAGRAM\",\"TIKTOK\"]}", 201).get("id").asLong();
        send("POST", "/api/content/posts/" + id + "/schedule", trainer, "{\"date\":\"" + LocalDate.now(ZoneId.of("Europe/Madrid")).plusDays(1)
                + "\",\"time\":\"11:00\",\"timeZone\":\"Europe/Madrid\"}", 200);
        ContentPost p = posts.findById(id).orElseThrow();
        p.setScheduledAt(Instant.now().minusSeconds(60));
        posts.save(p);
        return id;
    }

    private List<Notification> noticesFor(long postId) {
        return notifications.findAll().stream().filter(n -> Long.valueOf(postId).equals(n.getRefId()) && "CONTENT_POST".equals(n.getRefType())).toList();
    }

    @Test void publicaUnaVezLaRedAutomaticaYAvisaDeLaManual() throws Exception {
        connect(trainer, "k2");
        long id = scheduledPost("REEL");
        JsonNode before = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        assertTrue(before.get("targets").get(0).get("automatic").asBoolean());
        assertFalse(before.get("targets").get(1).get("automatic").asBoolean());

        job.runOnce(Instant.now());
        job.runOnce(Instant.now());
        assertFalse(job.process(id, Instant.now()));
        assertEquals(1, fake.published.size(), "como mucho una vez");
        JsonNode p = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        assertEquals("DUE", p.get("status").asText());
        assertEquals("PUBLISHED", p.get("targets").get(0).get("status").asText());
        assertTrue(p.get("targets").get(0).get("externalUrl").asText().startsWith("https://instagram.fake.test/p/"));
        assertEquals("MANUAL_DUE", p.get("targets").get(1).get("status").asText());
        List<Notification> n = noticesFor(id);
        assertEquals(1, n.size());
        assertEquals("Toca publicar: «Auto " + sfx + "» en TikTok", n.get(0).getTitle());

        assertEquals("PUBLISHED", send("POST", "/api/content/posts/" + id + "/targets/TIKTOK/published", trainer, null, 200).get("status").asText());
    }

    @Test void unFormatoQueLaRedNoPublicaVaAMano() throws Exception {
        connect(trainer, "k3");
        long id = scheduledPost("CAROUSEL");
        job.runOnce(Instant.now());
        assertEquals(0, fake.published.size());
        assertEquals("Toca publicar: «Auto " + sfx + "» en Instagram y TikTok", noticesFor(id).get(0).getTitle());
    }

    @Test void tokenRevocadoAlPublicarCaducaLaCuentaYPasaAMano() throws Exception {
        connect(trainer, "k4");
        fake.publishError = new SocialConnector.ConnectorException(SocialConnector.ConnectorException.Kind.EXPIRED, "revoked");
        long id = scheduledPost("REEL");
        job.runOnce(Instant.now());
        JsonNode p = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        assertEquals("MANUAL_DUE", p.get("targets").get(0).get("status").asText());
        assertEquals("EXPIRED", p.get("targets").get(0).get("accountStatus").asText());
        assertEquals(1, notifications.countByUserIdAndType(trainerUser.getId(), "SOCIAL_ACCOUNT_ATTENTION"));
        assertEquals(2, noticesFor(id).size());   // TikTok al tomarla, Instagram al caducar: una por red, sin repetir
        job.runOnce(Instant.now());
        assertEquals(2, noticesFor(id).size());
    }

    @Test void unRechazoDejaLaRedFallidaYAvisaUnaVezAlTerminar() throws Exception {
        connect(trainer, "k5");
        fake.publishError = new SocialConnector.ConnectorException(SocialConnector.ConnectorException.Kind.REJECTED, "bad media");
        long id = scheduledPost("REEL");
        job.runOnce(Instant.now());
        JsonNode p = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        assertEquals("FAILED", p.get("targets").get(0).get("status").asText());
        assertEquals("La red ha rechazado la publicación", p.get("targets").get(0).get("error").asText());
        assertEquals("DUE", p.get("status").asText());   // TikTok sigue pendiente a mano
        JsonNode done = send("POST", "/api/content/posts/" + id + "/targets/TIKTOK/published", trainer, null, 200);
        assertEquals("FAILED", done.get("status").asText());
        assertEquals(1, noticesFor(id).stream().filter(x -> "CONTENT_POST_FAILED".equals(x.getType())).count());
        // Marcar a mano la fallida la deja publicada.
        assertEquals("PUBLISHED", send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer, null, 200).get("status").asText());
    }

    // ---------- Cifrado ----------

    @Test void cifradoIdaYVueltaRotacionYClaveAusente() {
        String k1 = randomKey(), k2 = randomKey();
        TokenCipher c1 = new TokenCipher(k1, "");
        String enc1 = c1.encrypt("secreto", "aad:1");
        assertNotEquals(enc1, c1.encrypt("secreto", "aad:1"), "IV aleatorio");
        assertEquals("secreto", c1.decrypt(enc1, "aad:1"));
        assertThrows(TokenCipher.DecryptException.class, () -> c1.decrypt(enc1, "aad:2"));
        String tampered = enc1.substring(0, enc1.length() - 2) + (enc1.endsWith("A") ? "BB" : "AA");
        assertThrows(TokenCipher.DecryptException.class, () -> c1.decrypt(tampered, "aad:1"));
        assertFalse(c1.needsReencrypt(enc1));

        TokenCipher rotated = new TokenCipher(k2, k1);
        assertEquals("secreto", rotated.decrypt(enc1, "aad:1"));
        assertTrue(rotated.needsReencrypt(enc1));
        assertFalse(rotated.needsReencrypt(rotated.encrypt("x", "a")));
        assertThrows(TokenCipher.DecryptException.class, () -> new TokenCipher(k2, "").decrypt(enc1, "aad:1"));

        TokenCipher none = new TokenCipher("", "");
        assertFalse(none.available());
        assertThrows(IllegalStateException.class, () -> none.encrypt("x", "a"));
        assertThrows(IllegalStateException.class, () -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16]), ""));
        assertThrows(IllegalStateException.class, () -> new TokenCipher("no-es-base64!", ""));
        assertFalse(new SocialConnector.TokenSet("abc", "def", null, null, null, null).toString().contains("abc"));
    }
}
