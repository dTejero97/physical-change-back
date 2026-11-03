package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.content.ContentPublishJob;
import com.physicalchange.service.ai.AiProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Generador de contenido en la Fase 8.1 (docs/api-contenido.md, ADR-014): sin SOCIAL_TOKEN_KEY las 8 redes están NOT_CONFIGURED.
 * Permisos e IDOR entre profesionales, banco de solo lectura, programación y aviso idempotente del job, métricas sin inventar, IA.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentFlowTest {

    static class FakeAi implements AiProvider {
        final Deque<String> answers = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();
        @Override public boolean enabled() { return true; }
        @Override public String name() { return "fake"; }
        @Override public String model() { return "fake-model"; }
        @Override public synchronized Completion complete(String system, List<Turn> messages, String schema, int maxTokens) {
            sent.add(system + "\n" + messages.stream().map(Turn::content).reduce("", (a, b) -> a + "\n" + b));
            return new Completion(answers.isEmpty() ? "{}" : answers.poll(), 800, 400);
        }
    }

    @TestConfiguration
    static class Config { @Bean @Primary FakeAi fakeAi() { return new FakeAi(); } }

    @Autowired MockMvc mvc;
    @Autowired FakeAi fake;
    @Autowired UserRepository users;
    @Autowired TeamMemberRepository team;
    @Autowired ContentIdeaRepository ideas;
    @Autowired ContentPostRepository posts;
    @Autowired ContentPostMetricRepository metricRepo;
    @Autowired NotificationRepository notifications;
    @Autowired AiUsageRepository usage;
    @Autowired ContentPublishJob job;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx, trainer, other, admin, client, collab, collabNoPerm;
    private AppUser trainerUser;

    @BeforeEach void data() {
        fake.answers.clear();
        fake.sent.clear();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerUser = save("t-" + sfx + "@test", Role.TRAINER, null);
        AppUser o = save("o-" + sfx + "@test", Role.TRAINER, null);
        AppUser a = save("a-" + sfx + "@test", Role.ADMIN, null);
        AppUser c = save("c-" + sfx + "@test", Role.CLIENT, trainerUser.getId());
        trainer = token(trainerUser); other = token(o); admin = token(a); client = token(c);
        collab = token(collaborator("col-" + sfx + "@test", true));
        collabNoPerm = token(collaborator("coln-" + sfx + "@test", false));
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private AppUser collaborator(String email, boolean canUseContent) {
        AppUser u = save(email, Role.COLLABORATOR, trainerUser.getId());
        TeamMember m = new TeamMember(u.getId(), trainerUser.getId());
        m.activate();
        m.setCanUseContent(canUseContent);
        team.save(m);
        return u;
    }

    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private long post(String auth, String title, String networks) throws Exception {
        return send("POST", "/api/content/posts", auth, "{\"title\":\"" + title + "\",\"format\":\"REEL\",\"networks\":[" + networks + "]}", 201)
                .get("id").asLong();
    }

    private String tomorrow() { return LocalDate.now(ZoneId.of("Europe/Madrid")).plusDays(1).toString(); }

    /** Programa para mañana y mueve la hora al pasado (como si hubiera llegado). */
    private void scheduleAndExpire(long id) throws Exception {
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"11:00\",\"timeZone\":\"Europe/Madrid\"}", 200);
        ContentPost p = posts.findById(id).orElseThrow();
        p.setScheduledAt(Instant.now().minusSeconds(120));
        posts.save(p);
    }

    private long dueNotices(Long user, long postId) {
        return notifications.findAll().stream().filter(n -> n.getUserId().equals(user) && "CONTENT_POST_DUE".equals(n.getType())
                && Long.valueOf(postId).equals(n.getRefId())).count();
    }

    // ---------- Cuentas ----------

    @Test void sinClavesLasOchoRedesNoEstanDisponibles() throws Exception {
        JsonNode list = send("GET", "/api/content/accounts", trainer, null, 200);
        assertEquals(8, list.size());
        assertEquals("INSTAGRAM", list.get(0).get("network").asText());
        assertEquals("PINTEREST", list.get(7).get("network").asText());
        for (JsonNode a : list) {
            assertEquals("NOT_CONFIGURED", a.get("status").asText());
            assertFalse(a.get("configured").asBoolean());
            assertFalse(a.get("capabilities").get("publish").asBoolean());
            assertTrue(a.get("statusReason").asText().startsWith("No disponible todavía"));
            assertFalse(a.has("accessToken"));
        }
        send("POST", "/api/content/accounts/INSTAGRAM/connect", trainer, null, 409);
        send("POST", "/api/content/accounts/MYSPACE/connect", trainer, null, 404);
        send("POST", "/api/content/accounts/INSTAGRAM/disable", trainer, null, 404);
        send("DELETE", "/api/content/accounts/INSTAGRAM", trainer, null, 404);
        send("POST", "/api/content/oauth/callback", trainer, "{\"network\":\"INSTAGRAM\",\"state\":\"x\",\"code\":\"y\"}", 400);
        JsonNode catalog = send("GET", "/api/content/catalog", trainer, null, 200);
        assertEquals(8, catalog.get("networks").size());
        assertTrue(catalog.get("networks").get(0).get("charLimit").isNull());
        assertEquals("Europe/Madrid", catalog.get("timeZones").get(0).asText());
    }

    // ---------- Permisos ----------

    @Test void permisosPorRolYPermisoDelColaborador() throws Exception {
        send("GET", "/api/content/accounts", client, null, 403);
        send("GET", "/api/content/ideas", client, null, 403);
        send("POST", "/api/content/posts", client, "{\"title\":\"x\",\"format\":\"REEL\"}", 403);
        send("GET", "/api/content/accounts", collabNoPerm, null, 403);
        send("GET", "/api/content/home", collabNoPerm, null, 403);
        send("GET", "/api/content/accounts", collab, null, 200);
        send("POST", "/api/content/accounts/INSTAGRAM/connect", collab, null, 403);
        send("POST", "/api/content/oauth/callback", collab, "{\"network\":\"INSTAGRAM\",\"state\":\"x\",\"code\":\"y\"}", 403);
        send("POST", "/api/content/ai/ideas", collab, "{}", 403);
        send("GET", "/api/content/ideas", null, null, 401);

        // El colaborador con permiso trabaja en el espacio de su profesional y queda como autor.
        JsonNode p = send("POST", "/api/content/posts", collab, "{\"title\":\"Del equipo\",\"format\":\"POST\",\"networks\":[\"INSTAGRAM\"]}", 201);
        assertTrue(p.get("createdBy").get("fullName").asText().startsWith("U col-"));
        assertEquals("Del equipo", send("GET", "/api/content/posts/" + p.get("id").asLong(), trainer, null, 200).get("title").asText());
    }

    @Test void otroProfesionalOElAdminNoVenNiTocanLoAjeno() throws Exception {
        long idea = send("POST", "/api/content/ideas", trainer, "{\"title\":\"Mía\",\"format\":\"REEL\"}", 201).get("id").asLong();
        long postId = post(trainer, "Mía", "\"INSTAGRAM\"");
        long coll = send("POST", "/api/content/collections", trainer, "{\"name\":\"Favoritas\"}", 201).get("id").asLong();
        for (String auth : List.of(other, admin)) {
            send("GET", "/api/content/ideas/" + idea, auth, null, 404);
            send("PUT", "/api/content/ideas/" + idea, auth, "{\"title\":\"Robada\",\"format\":\"REEL\"}", 404);
            send("DELETE", "/api/content/ideas/" + idea, auth, null, 404);
            send("GET", "/api/content/posts/" + postId, auth, null, 404);
            send("POST", "/api/content/posts/" + postId + "/cancel", auth, null, 404);
            send("GET", "/api/content/posts/" + postId + "/metrics", auth, null, 404);
            send("GET", "/api/content/collections/" + coll + "/items", auth, null, 404);
            send("PUT", "/api/content/collections/" + coll + "/items/IDEA/" + idea, auth, null, 404);
            assertEquals(0, send("GET", "/api/content/ideas", auth, null, 200).get("totalElements").asInt());
            assertEquals(0, send("GET", "/api/content/posts", auth, null, 200).get("totalElements").asInt());
            // Ni siquiera usando la idea ajena en una publicación propia.
            send("POST", "/api/content/posts", auth, "{\"title\":\"x\",\"format\":\"REEL\",\"ideaId\":" + idea + "}", 404);
        }
        assertEquals("Mía", send("GET", "/api/content/ideas/" + idea, trainer, null, 200).get("title").asText());
    }

    // ---------- Banco, ideas y colecciones ----------

    @Test void elBancoEsDeSoloLecturaYSeCopiaUnaVez() throws Exception {
        ContentIdea b = new ContentIdea();
        b.setTitle("Banco " + sfx); b.setFormat("CAROUSEL"); b.setOrigin("BANK"); b.setHook("Gancho"); b.setScript("Guion"); b.setTheme("Fuerza");
        long bank = ideas.save(b).getId();
        JsonNode list = send("GET", "/api/content/ideas?scope=BANK&q=" + sfx, trainer, null, 200);
        assertEquals(1, list.get("totalElements").asInt());
        assertTrue(list.get("content").get(0).get("bank").asBoolean());
        send("PUT", "/api/content/ideas/" + bank, trainer, "{\"title\":\"x\",\"format\":\"REEL\"}", 409);
        send("PATCH", "/api/content/ideas/" + bank, trainer, "{\"favorite\":true}", 409);
        send("DELETE", "/api/content/ideas/" + bank, trainer, null, 409);
        JsonNode copy = send("POST", "/api/content/ideas/" + bank + "/copy", trainer, null, 201);
        assertEquals("BANK", copy.get("origin").asText());
        assertEquals(bank, copy.get("sourceId").asLong());
        assertFalse(copy.get("bank").asBoolean());
        assertEquals(copy.get("id").asLong(), send("POST", "/api/content/ideas/" + bank + "/copy", trainer, null, 200).get("id").asLong());
        send("POST", "/api/content/ideas/" + copy.get("id").asLong() + "/copy", trainer, null, 409);

        // A publicación desde el banco: usa la copia existente y la marca como usada.
        JsonNode p = send("POST", "/api/content/ideas/" + bank + "/to-post", trainer, "{\"networks\":[\"TIKTOK\"]}", 201);
        assertEquals("DRAFT", p.get("status").asText());
        assertEquals(copy.get("id").asLong(), p.get("ideaId").asLong());
        assertEquals("Gancho\n\nGuion", p.get("body").asText());
        assertEquals("CAROUSEL", p.get("format").asText());
        assertTrue(send("GET", "/api/content/ideas/" + copy.get("id").asLong(), trainer, null, 200).get("used").asBoolean());
        assertTrue(ideas.findById(bank).orElseThrow().getOwnerId() == null);

        // Otro profesional copia la misma idea del banco: su propia copia.
        assertNotEquals(copy.get("id").asLong(), send("POST", "/api/content/ideas/" + bank + "/copy", other, null, 201).get("id").asLong());
    }

    @Test void ideasGanchosYColecciones() throws Exception {
        JsonNode i = send("POST", "/api/content/ideas", trainer,
                "{\"title\":\"Sentadilla " + sfx + "\",\"format\":\"REEL\",\"objective\":\"AUTHORITY\",\"tags\":[\"Técnica\",\"técnica\",\"pierna\"]}", 201);
        assertEquals(List.of("Técnica", "pierna"), List.of(i.get("tags").get(0).asText(), i.get("tags").get(1).asText()));
        assertEquals(2, i.get("tags").size());
        assertEquals("OWN", i.get("origin").asText());
        send("POST", "/api/content/ideas", trainer, "{\"title\":\"x\",\"format\":\"TWEET\"}", 400);
        send("POST", "/api/content/ideas", trainer, "{\"title\":\"x\",\"format\":\"REEL\",\"tags\":[\"a,b\"]}", 400);
        assertEquals(1, send("GET", "/api/content/ideas?tag=pierna", trainer, null, 200).get("totalElements").asInt());
        assertEquals(0, send("GET", "/api/content/ideas?tag=pie", trainer, null, 200).get("totalElements").asInt());
        assertEquals(1, send("GET", "/api/content/ideas?objective=AUTHORITY&q=sentadilla", trainer, null, 200).get("totalElements").asInt());
        send("PATCH", "/api/content/ideas/" + i.get("id").asLong(), trainer, "{\"favorite\":true}", 200);
        assertEquals(1, send("GET", "/api/content/ideas?favorite=true", trainer, null, 200).get("totalElements").asInt());

        JsonNode hook = send("POST", "/api/content/hooks", trainer, "{\"text\":\"Deja de hacer esto\",\"cta\":\"ignorado\"}", 201);
        assertEquals("HOOK", hook.get("kind").asText());
        assertTrue(hook.get("cta").isNull());
        send("POST", "/api/content/hooks", trainer, "{\"text\":\"" + "x".repeat(301) + "\"}", 400);
        JsonNode copy = send("POST", "/api/content/copies", trainer,
                "{\"text\":\"Texto\",\"network\":\"LINKEDIN\",\"hashtags\":[\"#fitness\",\"salud\"]}", 201);
        assertEquals("fitness", copy.get("hashtags").get(0).asText());
        assertTrue(copy.get("charLimit").isNull());
        assertTrue(copy.get("overLimit").isNull());
        send("POST", "/api/content/copies", trainer, "{\"text\":\"x\",\"hashtags\":[\"dos palabras\"]}", 400);
        send("GET", "/api/content/hooks/" + copy.get("id").asLong(), trainer, null, 404);   // un copy no es un gancho
        assertEquals(1, send("GET", "/api/content/copies?network=LINKEDIN", trainer, null, 200).get("totalElements").asInt());

        long coll = send("POST", "/api/content/collections", trainer, "{\"name\":\"Pierna\"}", 201).get("id").asLong();
        send("POST", "/api/content/collections", trainer, "{\"name\":\"pierna\"}", 409);
        send("PUT", "/api/content/collections/" + coll + "/items/IDEA/" + i.get("id").asLong(), trainer, null, 204);
        send("PUT", "/api/content/collections/" + coll + "/items/IDEA/" + i.get("id").asLong(), trainer, null, 204);   // idempotente
        send("PUT", "/api/content/collections/" + coll + "/items/HOOK/" + hook.get("id").asLong(), trainer, null, 204);
        send("PUT", "/api/content/collections/" + coll + "/items/HOOK/" + copy.get("id").asLong(), trainer, null, 404);
        assertEquals(2, send("GET", "/api/content/collections", trainer, null, 200).get(0).get("itemCount").asInt());
        assertEquals(1, send("GET", "/api/content/collections/" + coll + "/items?type=HOOK", trainer, null, 200).get("totalElements").asInt());
        send("DELETE", "/api/content/ideas/" + i.get("id").asLong(), trainer, null, 204);
        JsonNode items = send("GET", "/api/content/collections/" + coll + "/items", trainer, null, 200);
        assertEquals(1, items.get("totalElements").asInt());
        assertEquals("HOOK", items.get("content").get(0).get("type").asText());
        send("DELETE", "/api/content/collections/" + coll, trainer, null, 204);
        assertEquals("Deja de hacer esto", send("GET", "/api/content/hooks/" + hook.get("id").asLong(), trainer, null, 200).get("text").asText());
    }

    // ---------- Publicaciones y job ----------

    @Test void programarValidaFechaRedesYZona() throws Exception {
        long id = post(trainer, "Fechas", "");
        String body = "{\"date\":\"" + tomorrow() + "\",\"time\":\"11:00\",\"timeZone\":\"Europe/Madrid\"}";
        send("POST", "/api/content/posts/" + id + "/schedule", trainer, body, 400);   // sin redes
        String yesterday = LocalDate.now(ZoneId.of("Europe/Madrid")).minusDays(1).toString();
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + yesterday + "\",\"time\":\"11:00\",\"timeZone\":\"Europe/Madrid\",\"networks\":[\"X\"]}", 400);
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"11:00\",\"timeZone\":\"UTC\",\"networks\":[\"X\"]}", 400);
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"2030-03-31\",\"time\":\"02:30\",\"timeZone\":\"Europe/Madrid\",\"networks\":[\"X\"]}", 400);   // no existe
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"2030-03-31\",\"time\":\"25:00\",\"timeZone\":\"Europe/Madrid\",\"networks\":[\"X\"]}", 400);
        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"11:00\",\"timeZone\":\"Europe/Madrid\",\"networks\":[\"X\",\"X\"]}", 400);
        // Hora repetida (último domingo de octubre): la primera, aún en horario de verano (+02:00).
        JsonNode p = send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"2030-10-27\",\"time\":\"02:30\",\"timeZone\":\"Europe/Madrid\",\"networks\":[\"X\",\"LINKEDIN\"]}", 200);
        assertEquals("SCHEDULED", p.get("status").asText());
        assertEquals("2030-10-27T02:30", p.get("scheduledLocal").asText());
        assertTrue(p.get("scheduledAt").asText().endsWith("+02:00"));
        assertEquals(2, p.get("targets").size());
        assertFalse(p.get("targets").get(0).get("automatic").asBoolean());
        assertEquals("NOT_CONFIGURED", p.get("targets").get(0).get("accountStatus").asText());
        send("POST", "/api/content/posts/" + id + "/schedule", trainer, body, 409);   // ya programada: se mueve con PATCH

        // Mover en el calendario con la versión vista (D-05): una versión vieja da 409.
        int v = p.get("version").asInt();
        JsonNode moved = send("PATCH", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"2030-11-04\",\"time\":\"09:15\",\"timeZone\":\"Europe/Madrid\",\"version\":" + v + "}", 200);
        assertEquals("2030-11-04T09:15", moved.get("scheduledLocal").asText());
        assertTrue(moved.get("version").asInt() > v);
        send("PATCH", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"2030-11-05\",\"time\":\"09:15\",\"timeZone\":\"Europe/Madrid\",\"version\":" + v + "}", 409);
        JsonNode cal = send("GET", "/api/content/calendar?view=MONTH&date=2030-11-04", trainer, null, 200);
        assertEquals("2030-10-28", cal.get("from").asText());
        assertEquals("2030-12-01", cal.get("to").asText());
        JsonNode day = java.util.stream.StreamSupport.stream(cal.get("days").spliterator(), false)
                .filter(d -> "2030-11-04".equals(d.get("date").asText())).findFirst().orElseThrow();
        assertEquals("09:15", day.get("posts").get(0).get("time").asText());
        assertEquals(moved.get("version").asInt(), day.get("posts").get(0).get("version").asInt());

        JsonNode back = send("POST", "/api/content/posts/" + id + "/unschedule", trainer, null, 200);
        assertEquals("DRAFT", back.get("status").asText());
        assertTrue(back.get("scheduledAt").isNull());
    }

    @Test void editarBorrarYCancelarRespetanElEstado() throws Exception {
        long id = post(trainer, "Estados", "\"INSTAGRAM\"");
        JsonNode p = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        int v = p.get("version").asInt();
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"Sin versión\",\"format\":\"REEL\"}", 400);
        JsonNode upd = send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"Nuevo\",\"format\":\"REEL\",\"version\":" + v + ",\"networks\":[\"TIKTOK\"]}", 200);
        assertEquals("TIKTOK", upd.get("targets").get(0).get("network").asText());
        assertEquals(1, upd.get("targets").size());
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"Viejo\",\"format\":\"REEL\",\"version\":" + v + "}", 409);
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"x\",\"format\":\"REEL\",\"mediaUrl\":\"http://inseguro\",\"version\":"
                + upd.get("version").asInt() + "}", 400);
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"x\",\"format\":\"REEL\",\"mediaFileId\":999999,\"version\":"
                + upd.get("version").asInt() + "}", 404);

        send("POST", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"10:00\",\"timeZone\":\"Europe/Madrid\"}", 200);
        send("DELETE", "/api/content/posts/" + id, trainer, null, 409);
        JsonNode c = send("POST", "/api/content/posts/" + id + "/cancel", trainer, null, 200);
        assertEquals("CANCELLED", c.get("status").asText());
        assertEquals("CANCELLED", c.get("targets").get(0).get("status").asText());
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"x\",\"format\":\"REEL\",\"version\":" + c.get("version").asInt() + "}", 409);
        JsonNode r = send("POST", "/api/content/posts/" + id + "/restore", trainer, null, 200);
        assertEquals("DRAFT", r.get("status").asText());
        assertEquals("PENDING", r.get("targets").get(0).get("status").asText());
        JsonNode dup = send("POST", "/api/content/posts/" + id + "/duplicate", trainer, null, 201);
        assertEquals("DRAFT", dup.get("status").asText());
        assertNotEquals(id, dup.get("id").asLong());
        send("DELETE", "/api/content/posts/" + id, trainer, null, 204);
        send("GET", "/api/content/posts/" + id, trainer, null, 404);
    }

    @Test void elJobAvisaUnaSolaVezYNoPublicaSinConector() throws Exception {
        long id = post(trainer, "Toca " + sfx, "\"INSTAGRAM\",\"TIKTOK\"");
        scheduleAndExpire(id);
        assertTrue(job.runOnce(Instant.now()) >= 1);
        JsonNode p = send("GET", "/api/content/posts/" + id, trainer, null, 200);
        assertEquals("DUE", p.get("status").asText());
        assertEquals("MANUAL_DUE", p.get("targets").get(0).get("status").asText());
        assertEquals("MANUAL_DUE", p.get("targets").get(1).get("status").asText());
        assertEquals(1, dueNotices(trainerUser.getId(), id));
        Notification n = notifications.findAll().stream().filter(x -> Long.valueOf(id).equals(x.getRefId()) && "CONTENT_POST_DUE".equals(x.getType()))
                .findFirst().orElseThrow();
        assertEquals("Toca publicar: «Toca " + sfx + "» en Instagram y TikTok", n.getTitle());
        assertEquals("CONTENT_POST", n.getRefType());

        // Reintentos y otra "instancia": ni otro aviso ni otro cambio.
        job.runOnce(Instant.now());
        assertFalse(job.process(id, Instant.now()));
        assertEquals(1, dueNotices(trainerUser.getId(), id));
        assertEquals(1, send("GET", "/api/content/home", trainer, null, 200).get("manualDue").asLong());
        assertEquals(1, send("GET", "/api/content/posts/manual-due/count", collab, null, 200).get("count").asLong());

        // Marcar a mano: enlace solo https; al publicar todas, PUBLISHED.
        send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer, "{\"externalUrl\":\"http://x.test/p\"}", 400);
        send("POST", "/api/content/posts/" + id + "/targets/YOUTUBE/published", trainer, "{}", 404);
        send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer,
                "{\"publishedAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}", 400);
        JsonNode one = send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer, "{\"externalUrl\":\"https://x.test/p\"}", 200);
        assertEquals("DUE", one.get("status").asText());
        send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer, "{}", 409);
        JsonNode all = send("POST", "/api/content/posts/" + id + "/targets/TIKTOK/published", collab, null, 200);
        assertEquals("PUBLISHED", all.get("status").asText());
        send("POST", "/api/content/posts/" + id + "/cancel", trainer, null, 409);
        assertEquals(0, send("GET", "/api/content/home", trainer, null, 200).get("manualDue").asLong());
        assertEquals(1, dueNotices(trainerUser.getId(), id));
    }

    @Test void dosInstanciasALaVezSoloUnaTomaLaPublicacion() throws Exception {
        long id = post(trainer, "Carrera", "\"X\"");
        scheduleAndExpire(id);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        List<Future<?>> f = new ArrayList<>();
        for (int k = 0; k < 4; k++) f.add(pool.submit(() -> {
            start.await();
            try { if (job.process(id, Instant.now())) won.incrementAndGet(); } catch (RuntimeException ignored) { /* bloqueo de fila: pierde */ }
            return null;
        }));
        start.countDown();
        for (Future<?> x : f) x.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, won.get());
        assertEquals(1, dueNotices(trainerUser.getId(), id));
    }

    @Test void unaEdicionConcurrenteConElJobNoVuelveAProgramar() throws Exception {
        long id = post(trainer, "Concurrente", "\"X\"");
        scheduleAndExpire(id);
        int seen = send("GET", "/api/content/posts/" + id, trainer, null, 200).get("version").asInt();
        job.runOnce(Instant.now());
        // El usuario edita con la versión de antes del job: 409 y la publicación sigue DUE.
        send("PUT", "/api/content/posts/" + id, trainer, "{\"title\":\"Tarde\",\"format\":\"REEL\",\"version\":" + seen + "}", 409);
        send("PATCH", "/api/content/posts/" + id + "/schedule", trainer,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"10:00\",\"timeZone\":\"Europe/Madrid\",\"version\":" + seen + "}", 409);
        assertEquals("DUE", posts.findById(id).orElseThrow().getStatus());
        job.runOnce(Instant.now());
        assertEquals(1, dueNotices(trainerUser.getId(), id));
    }

    // ---------- Métricas y analíticas ----------

    @Test void metricasManualesSinInventarCifras() throws Exception {
        long id = post(trainer, "Medible", "\"INSTAGRAM\",\"TIKTOK\"");
        scheduleAndExpire(id);
        job.runOnce(Instant.now());
        String today = LocalDate.now(ZoneId.of("Europe/Madrid")).toString();
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, "{\"views\":10}", 409);   // aún no publicada
        send("POST", "/api/content/posts/" + id + "/targets/INSTAGRAM/published", trainer, null, 200);

        JsonNode s = send("GET", "/api/content/analytics/summary", trainer, null, 200);
        JsonNode k = s.get("kpis");
        assertEquals(1, k.get("scheduled").get("value").asInt());
        assertEquals(1, k.get("published").get("value").asInt());
        assertTrue(k.get("views").get("value").isNull(), "sin métricas: Sin datos, no 0");
        assertTrue(k.get("interactions").get("value").isNull());
        assertTrue(k.get("engagementRate").get("value").isNull());
        assertTrue(k.get("followersGained").get("value").isNull());
        assertTrue(k.get("views").get("changePct").isNull());
        assertEquals(0, s.get("topPosts").size());
        for (JsonNode d : s.get("viewsByDay")) assertTrue(d.get("value").isNull());
        assertTrue(s.get("interactionsByType").get("likes").isNull());
        assertFalse(send("GET", "/api/content/posts/" + id, trainer, null, 200).get("hasMetrics").asBoolean());

        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, "{}", 400);
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, "{\"views\":-1}", 400);
        send("PUT", "/api/content/posts/" + id + "/metrics/TIKTOK/" + today, trainer, "{\"views\":10}", 409);
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + LocalDate.parse(today).plusDays(1), trainer, "{\"views\":10}", 409);
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + LocalDate.parse(today).minusDays(3), trainer, "{\"views\":10}", 409);
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, other, "{\"views\":10}", 404);
        JsonNode m = send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, collab, "{\"views\":200,\"likes\":8,\"comments\":2}", 200);
        assertEquals("MANUAL", m.get("source").asText());
        assertTrue(m.get("reach").isNull());
        // Sustituye la del mismo día.
        send("PUT", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, "{\"views\":400,\"likes\":16,\"comments\":4}", 200);
        assertEquals(1, send("GET", "/api/content/posts/" + id + "/metrics", trainer, null, 200).size());
        assertTrue(send("GET", "/api/content/posts/" + id, trainer, null, 200).get("hasMetrics").asBoolean());

        k = send("GET", "/api/content/analytics/summary", trainer, null, 200).get("kpis");
        assertEquals(400, k.get("views").get("value").asInt());
        assertEquals("MANUAL", k.get("views").get("source").asText());
        assertEquals(20, k.get("interactions").get("value").asInt());
        assertEquals(0, k.get("engagementRate").get("value").decimalValue().compareTo(new java.math.BigDecimal("5.00")));
        assertTrue(k.get("reach").get("value").isNull(), "el alcance no se ha apuntado: sigue sin datos");
        JsonNode s2 = send("GET", "/api/content/analytics/summary?network=INSTAGRAM", trainer, null, 200);
        assertEquals(1, s2.get("topPosts").size());
        assertEquals(16, s2.get("interactionsByType").get("likes").asInt());
        assertTrue(s2.get("interactionsByType").get("shares").isNull());
        assertEquals(1, s2.get("heatmap").size());
        assertEquals(1, s2.get("formats").get(0).get("count").asInt());
        assertTrue(send("GET", "/api/content/analytics/summary?network=TIKTOK", trainer, null, 200).get("kpis").get("views").get("value").isNull());
        send("GET", "/api/content/analytics/summary?from=2020-01-01&to=2026-01-01", trainer, null, 400);

        // Si el mismo día hay una sincronizada, gana; y esa no se borra.
        ContentPostMetric sync = new ContentPostMetric(id, "INSTAGRAM", LocalDate.parse(today), "SYNC", null);
        sync.setValues(500L, null, null, null, null, null);
        metricRepo.save(sync);
        k = send("GET", "/api/content/analytics/summary", trainer, null, 200).get("kpis");
        assertEquals(500, k.get("views").get("value").asInt());
        assertEquals("SYNC", k.get("views").get("source").asText());
        assertTrue(k.get("interactions").get("value").isNull());
        send("DELETE", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, null, 204);   // borra la manual
        send("DELETE", "/api/content/posts/" + id + "/metrics/INSTAGRAM/" + today, trainer, null, 409);   // queda la sincronizada
        send("DELETE", "/api/content/posts/" + id + "/metrics/INSTAGRAM/2001-01-01", trainer, null, 404);
    }

    @Test void inicioYCalendarioSinDatosAjenos() throws Exception {
        long id = post(other, "Ajena", "\"X\"");
        send("POST", "/api/content/posts/" + id + "/schedule", other,
                "{\"date\":\"" + tomorrow() + "\",\"time\":\"10:00\",\"timeZone\":\"Europe/Madrid\"}", 200);
        JsonNode home = send("GET", "/api/content/home?date=" + tomorrow(), trainer, null, 200);
        assertEquals(7, home.get("week").get("days").size());
        for (JsonNode d : home.get("week").get("days")) assertEquals(0, d.get("posts").size());
        assertEquals(0, home.get("kpis").get("scheduled").asInt());
        assertTrue(home.get("kpis").get("views").isNull());
        assertTrue(home.get("kpis").get("onTimeRate").isNull());
        assertEquals(8, home.get("accounts").size());
        JsonNode mine = send("GET", "/api/content/home?date=" + tomorrow(), other, null, 200);
        assertEquals(1, mine.get("kpis").get("scheduled").asInt());
        send("GET", "/api/content/calendar?view=YEAR", trainer, null, 400);
    }

    // ---------- Physical AI ----------

    @Test void iaGeneraIdeasYCobraUnCredito() throws Exception {
        fake.answers.add("{\"ideas\":[{\"title\":\"Idea A\",\"format\":\"REEL\",\"objective\":\"AUTHORITY\",\"tags\":[\"a,b\"]},"
                + "{\"title\":\"Idea B\",\"format\":\"PODCAST\"},{\"title\":\"\",\"format\":\"POST\"},{\"title\":\"Idea C\",\"format\":\"POST\"}]}");
        JsonNode r = send("POST", "/api/content/ai/ideas", trainer, "{\"count\":3,\"theme\":\"Fuerza\",\"prompt\":\"para principiantes\"}", 201);
        assertEquals("IDEAS", r.get("kind").asText());
        assertEquals(2, r.get("ideas").size());
        assertEquals("AI", r.get("ideas").get(0).get("origin").asText());
        assertEquals("Fuerza", r.get("ideas").get(0).get("theme").asText());
        assertEquals(1, r.get("creditsUsed").asInt());
        assertEquals(1, r.get("credits").get("used").asInt());
        assertEquals(1, r.get("warnings").size());
        assertTrue(fake.sent.get(0).contains("para principiantes"));
        assertFalse(fake.sent.get(0).contains("c-" + sfx), "nunca datos de clientes");
        assertEquals(1, usage.countByTrainerIdAndKindAndCreatedAtGreaterThanEqual(trainerUser.getId(), "CONTENT_IDEA", LocalDate.now().atStartOfDay()));

        // Salida no válida dos veces: 422 y no se cobra.
        fake.answers.add("no es json"); fake.answers.add("{\"otra\":1}");
        send("POST", "/api/content/ai/hooks", trainer, "{\"count\":3}", 422);
        assertEquals(1, send("GET", "/api/ai/status", trainer, null, 200).get("credits").get("used").asInt());

        fake.answers.add("{\"copies\":[{\"network\":\"INSTAGRAM\",\"text\":\"Copy IG\",\"hashtags\":[\"#fit ness\",\"gym\"]},"
                + "{\"network\":\"MYSPACE\",\"text\":\"x\"}]}");
        JsonNode c = send("POST", "/api/content/ai/copies", trainer, "{\"networks\":[\"INSTAGRAM\",\"X\"],\"topic\":\"Sentadilla\",\"includeHashtags\":true}", 201);
        assertEquals(1, c.get("snippets").size());
        assertEquals("INSTAGRAM", c.get("snippets").get(0).get("network").asText());
        assertEquals("fitness", c.get("snippets").get(0).get("hashtags").get(0).asText());
        assertTrue(c.get("warnings").toString().contains("No se ha generado el copy para X"));
        assertTrue(c.get("warnings").toString().contains("sin verificar"));
        send("POST", "/api/content/ai/copies", trainer, "{\"networks\":[\"INSTAGRAM\"]}", 400);
        long foreign = send("POST", "/api/content/ideas", other, "{\"title\":\"Ajena\",\"format\":\"REEL\"}", 201).get("id").asLong();
        send("POST", "/api/content/ai/copies", trainer, "{\"networks\":[\"INSTAGRAM\"],\"ideaId\":" + foreign + "}", 404);
        assertEquals(2, send("GET", "/api/ai/status", trainer, null, 200).get("credits").get("used").asInt());
    }

    @Test void iaGeneraGanchosHastaAgotarLosCreditos() throws Exception {
        int total = send("GET", "/api/ai/status", trainer, null, 200).get("credits").get("total").asInt();
        for (int i = 0; i < total; i++) {
            fake.answers.add("{\"hooks\":[{\"text\":\"Gancho " + i + "\",\"objective\":\"NO_VALE\"},{\"text\":\"  \"}]}");
            JsonNode r = send("POST", "/api/content/ai/hooks", trainer, "{\"count\":3,\"objective\":\"COMMUNITY\"}", 201);
            assertEquals("HOOKS", r.get("kind").asText());
            assertEquals(1, r.get("snippets").size());
            assertEquals("HOOK", r.get("snippets").get(0).get("kind").asText());
            assertEquals("COMMUNITY", r.get("snippets").get(0).get("objective").asText());
            assertEquals("AI", r.get("snippets").get(0).get("origin").asText());
        }
        long before = send("GET", "/api/content/hooks?size=100", trainer, null, 200).get("totalElements").asLong();
        fake.answers.add("{\"hooks\":[{\"text\":\"Sin crédito\"}]}");
        send("POST", "/api/content/ai/hooks", trainer, "{\"count\":3}", 402);
        assertEquals(before, send("GET", "/api/content/hooks?size=100", trainer, null, 200).get("totalElements").asLong());
        assertEquals(total, send("GET", "/api/ai/status", trainer, null, 200).get("credits").get("used").asInt());
        // El colaborador, aunque tenga el permiso del generador, no usa Physical AI.
        send("POST", "/api/content/ai/hooks", collab, "{\"count\":3}", 403);
    }
}
