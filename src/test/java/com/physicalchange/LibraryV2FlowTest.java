package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Librería V2 contra la base de datos: archivos, documentos, programas, formularios, plantillas y logros (docs/api-libreria-v2.md). */
@SpringBootTest
@AutoConfigureMockMvc
class LibraryV2FlowTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    static final byte[] PDF = "%PDF-1.4 prueba".getBytes();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired WorkoutRepository workouts;
    @Autowired WorkoutItemRepository workoutItems;
    @Autowired TemplatePackRepository packs;
    @Autowired TemplateItemRepository templateItems;
    @Autowired AchievementCategoryRepository categories;
    @Autowired AchievementRepository achievements;
    @Autowired ExerciseRepository exercises;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx;
    private Long trainerId, otherTrainerId;
    private String trainer, otherTrainer, client;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        Long clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private JsonNode upload(String auth, String name, byte[] bytes, String purpose, int status) throws Exception {
        var req = multipart("/api/files").file(new MockMultipartFile("file", name, "application/octet-stream", bytes)).param("purpose", purpose);
        if (auth != null) req.header("Authorization", auth);
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private JsonNode read(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    @Test void archivosConFirmaYUrlFirmada() throws Exception {
        JsonNode f = upload(trainer, "logo.png", PNG, "IMAGE", 201);
        assertEquals("image/png", f.get("contentType").asText());
        upload(trainer, "falso.png", PDF, "IMAGE", 400);                      // la extensión no coincide con el contenido
        upload(trainer, "dibujo.svg", "<svg/>".getBytes(), "IMAGE", 400);     // SVG no permitido
        upload(trainer, "doc.pdf", PDF, "IMAGE", 400);                        // un PDF no es una imagen
        upload(trainer, "x.png", PNG, "OTRO", 400);
        upload(trainer, "enorme.pdf", new byte[(int) (10 * 1024 * 1024) + 1], "DOCUMENT", 413);
        upload(client, "logo.png", PNG, "IMAGE", 403);
        upload(null, "logo.png", PNG, "IMAGE", 401);

        // Descarga pública con URL firmada; sin firma válida, 403
        String url = f.get("url").asText();
        assertTrue(url.startsWith("/public/files/"));
        mvc.perform(get("/api" + url)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mvc.perform(get("/api" + url.replaceAll("sig=.*", "sig=AAAA"))).andExpect(status().isForbidden());
        mvc.perform(get("/api" + url.replaceAll("exp=\\d+", "exp=1000"))).andExpect(status().isForbidden());

        send("DELETE", "/api/files/" + f.get("id").asLong(), otherTrainer, null, 403);
        send("DELETE", "/api/files/" + f.get("id").asLong(), trainer, null, 204);
    }

    @Test void documentos() throws Exception {
        long file = upload(trainer, "Pautas de descanso.pdf", PDF, "DOCUMENT", 201).get("id").asLong();
        long tag = send("POST", "/api/tags", trainer, "{\"name\":\"doc-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        String body = "{\"name\":\"Pautas de descanso\",\"description\":\"Recuperación\",\"visibility\":\"TAGS\",\"tagIds\":[" + tag + "],\"fileId\":" + file + "}";
        send("POST", "/api/library/documents", trainer, body.replace("[" + tag + "]", "[]"), 400);      // TAGS sin etiquetas
        send("POST", "/api/library/documents", otherTrainer, body.replace("[" + tag + "]", "[]").replace("TAGS", "ALL"), 403); // archivo ajeno
        JsonNode d = send("POST", "/api/library/documents", trainer, body, 201);
        assertEquals("Pautas de descanso.pdf", d.get("fileName").asText());
        assertEquals("application/pdf", d.get("contentType").asText());
        send("POST", "/api/library/documents", trainer, body, 409);                                   // archivo ya usado
        assertEquals(1, send("GET", "/api/library/documents", trainer, null, 200).size());
        assertEquals(0, send("GET", "/api/library/documents", otherTrainer, null, 200).size());
        send("GET", "/api/library/documents", client, null, 403);

        // Cambiar el archivo borra el anterior; borrar el documento borra su archivo
        String oldUrl = d.get("url").asText();
        long file2 = upload(trainer, "v2.pdf", PDF, "DOCUMENT", 201).get("id").asLong();
        long id = d.get("id").asLong();
        send("PUT", "/api/library/documents/" + id, trainer, body.replace("\"fileId\":" + file, "\"fileId\":" + file2), 200);
        mvc.perform(get("/api" + oldUrl)).andExpect(status().isNotFound());
        send("DELETE", "/api/library/documents/" + id, otherTrainer, null, 403);
        send("DELETE", "/api/library/documents/" + id, trainer, null, 204);
        send("DELETE", "/api/files/" + file2, trainer, null, 404);
    }

    @Test void programas() throws Exception {
        Workout w = new Workout(); w.setTrainerId(trainerId); w.setName("Full body " + sfx); w = workouts.save(w);
        Workout foreign = new Workout(); foreign.setTrainerId(otherTrainerId); foreign.setName("Ajeno"); foreign = workouts.save(foreign);
        String items = "[{\"week\":1,\"day\":1,\"kind\":\"WORKOUT\",\"refId\":" + w.getId() + ",\"title\":\"Full body\"}," +
                "{\"week\":1,\"day\":1,\"kind\":\"CARDIO\",\"refId\":7,\"title\":\"Carrera suave\"},{\"week\":2,\"day\":3,\"kind\":\"NOTE\",\"title\":\"Descanso activo\"}]";
        String body = "{\"name\":\"Programa 3 semanas\",\"description\":\"Completo\",\"weeks\":2,\"archiveClient\":false,\"items\":" + items + "}";
        send("POST", "/api/library/programs", trainer, body.replace("\"refId\":" + w.getId(), "\"refId\":" + foreign.getId()), 400);
        send("POST", "/api/library/programs", trainer, body.replace("\"weeks\":2", "\"weeks\":1"), 400);    // semana 2 no existe
        JsonNode p = send("POST", "/api/library/programs", trainer, body, 201);
        long id = p.get("program").get("id").asLong();
        assertEquals(3, p.get("items").size());
        assertEquals(2, p.get("items").get(1).get("position").asInt());           // segundo elemento del día 1
        assertFalse(p.get("program").get("automated").asBoolean());

        // Enlazar otro programa y archivar: queda automatizado
        long second = send("POST", "/api/library/programs/" + id + "/duplicate", trainer, null, 201).get("program").get("id").asLong();
        send("PUT", "/api/library/programs/" + id, trainer, body.replace("\"archiveClient\":false", "\"linkedProgramId\":" + id + ",\"archiveClient\":false"), 400);
        JsonNode upd = send("PUT", "/api/library/programs/" + id, trainer, body.replace("\"archiveClient\":false", "\"linkedProgramId\":" + second + ",\"archiveClient\":true"), 200);
        assertTrue(upd.get("program").get("automated").asBoolean());
        assertEquals("Programa 3 semanas (copia)", upd.get("program").get("linkedProgramName").asText());
        assertEquals(2, send("GET", "/api/library/programs", trainer, null, 200).size());
        send("GET", "/api/library/programs/" + id, otherTrainer, null, 403);

        // Borrar el enlazado deja al otro sin enlace
        send("DELETE", "/api/library/programs/" + second, trainer, null, 204);
        assertTrue(send("GET", "/api/library/programs/" + id, trainer, null, 200).get("program").get("linkedProgramId").isNull());
    }

    @Test void formularios() throws Exception {
        String q = "[{\"kind\":\"SHORT_TEXT\",\"label\":\"¿Objetivo principal?\",\"required\":true}," +
                "{\"kind\":\"SINGLE\",\"label\":\"¿Días por semana?\",\"required\":false,\"options\":[\"2\",\"3\",\"4+\"]}]";
        String body = "{\"name\":\"Cuestionario inicial\",\"description\":\"Para empezar\",\"autoSend\":true,\"questions\":" + q + "}";
        send("POST", "/api/library/forms", trainer, body.replace("[\"2\",\"3\",\"4+\"]", "[\"2\"]"), 400);  // opción única con 1 opción
        send("POST", "/api/library/forms", trainer, body.replace("SHORT_TEXT", "AUDIO"), 400);
        JsonNode f = send("POST", "/api/library/forms", trainer, body, 201);
        long id = f.get("form").get("id").asLong();
        assertEquals(2, f.get("form").get("questionCount").asInt());
        assertEquals(3, f.get("questions").get(1).get("options").size());

        JsonNode upd = send("PUT", "/api/library/forms/" + id, trainer, body.replace(q, "[{\"kind\":\"SCALE\",\"label\":\"Energía\",\"required\":true}]"), 200);
        assertEquals(1, upd.get("questions").size());
        assertEquals(0, upd.get("questions").get(0).get("options").size());
        JsonNode copy = send("POST", "/api/library/forms/" + id + "/duplicate", trainer, null, 201);
        assertFalse(copy.get("form").get("autoSend").asBoolean());
        assertEquals(2, send("GET", "/api/library/forms", trainer, null, 200).size());
        send("PUT", "/api/library/forms/" + id, otherTrainer, body, 403);
        send("DELETE", "/api/library/forms/" + id, trainer, null, 204);
        send("GET", "/api/library/forms/" + id, trainer, null, 404);
    }

    @Test void plantillasSeCopianALaLibreria() throws Exception {
        TemplatePack wp = pack("WORKOUT", "Gimnasio " + sfx);
        TemplateItem wi = item(wp, "Empuje " + sfx, "6 ejercicios",
                "{\"exercises\":[{\"name\":\"Press banca " + sfx + "\",\"muscle\":\"Pecho\",\"sets\":4,\"reps\":8,\"rest\":120}," +
                "{\"name\":\"Fondos " + sfx + "\",\"muscle\":\"Brazos\",\"sets\":3,\"reps\":10}]}");
        TemplatePack pp = pack("PROGRAM", "Programas " + sfx);
        TemplateItem pi = item(pp, "Iniciación " + sfx, "2 semanas",
                "{\"weeks\":2,\"workouts\":[{\"name\":\"A " + sfx + "\",\"exercises\":[{\"name\":\"Press banca " + sfx + "\",\"sets\":3,\"reps\":10}]}]," +
                "\"schedule\":[{\"week\":1,\"day\":1,\"workout\":0},{\"week\":2,\"day\":1,\"workout\":0}]}");

        assertTrue(send("GET", "/api/library/templates", trainer, null, 200).toString().contains("Gimnasio " + sfx));
        assertEquals(1, send("GET", "/api/library/templates/" + wp.getId(), trainer, null, 200).get("items").size());

        JsonNode copied = send("POST", "/api/library/templates/items/" + wi.getId() + "/copy", trainer, null, 201);
        assertEquals("WORKOUT", copied.get("kind").asText());
        assertEquals(2, workoutItems.findByWorkoutIdOrderByPositionAsc(copied.get("id").asLong()).size());
        assertEquals(trainerId, workouts.findById(copied.get("id").asLong()).orElseThrow().getTrainerId());

        // El programa reutiliza el ejercicio "Press banca" ya creado y coloca el workout en 2 semanas
        long before = exercises.count();
        JsonNode prog = send("POST", "/api/library/templates/items/" + pi.getId() + "/copy", trainer, null, 201);
        assertEquals(before, exercises.count());
        JsonNode detail = send("GET", "/api/library/programs/" + prog.get("id").asLong(), trainer, null, 200);
        assertEquals(2, detail.get("program").get("weeks").asInt());
        assertEquals(2, detail.get("items").size());
        send("POST", "/api/library/templates/items/" + wi.getId() + "/copy", client, null, 403);
    }

    @Test void logros() throws Exception {
        AchievementCategory sys = new AchievementCategory();
        sys.setSystemKey("T" + sfx); sys.setName("Hitos " + sfx); sys.setIcon("flag"); sys.setPosition(2);
        sys = categories.save(sys);
        Achievement hito = new Achievement();
        hito.setCategoryId(sys.getId()); hito.setName("5 workouts completados"); hito.setConditionType("SYSTEM"); hito.setBadge("workouts"); hito.setTier("BRONZE");
        hito = achievements.save(hito);
        Exercise ex = new Exercise(); ex.setName("Sentadilla " + sfx); ex = exercises.save(ex);

        JsonNode cats = send("GET", "/api/library/achievements", trainer, null, 200);
        JsonNode personal = cats.get(0);
        assertEquals("Logros personalizados", personal.get("name").asText());
        assertEquals("HIDDEN", personal.get("visibility").asText());
        assertFalse(personal.get("system").asBoolean());
        long personalId = personal.get("id").asLong();

        String a = "{\"categoryId\":" + personalId + ",\"name\":\"100 kg en sentadilla\",\"conditionType\":\"WEIGHT_EXERCISE\",\"exerciseId\":" + ex.getId() +
                ",\"weightKg\":100,\"durationType\":\"UNLIMITED\"}";
        send("POST", "/api/library/achievements", trainer, a.replace("\"categoryId\":" + personalId, "\"categoryId\":" + sys.getId()), 400); // categoría del sistema
        send("POST", "/api/library/achievements", trainer, a.replace(",\"weightKg\":100", ""), 400);                       // falta el peso
        send("POST", "/api/library/achievements", trainer, a.replace("UNLIMITED", "DATE_RANGE"), 400);                       // faltan fechas
        JsonNode created = send("POST", "/api/library/achievements", trainer, a, 201);
        assertEquals("Sentadilla " + sfx, created.get("exerciseName").asText());
        long achId = created.get("id").asLong();
        send("PUT", "/api/library/achievements/" + achId, otherTrainer, a, 403);
        send("PUT", "/api/library/achievements/" + hito.getId(), trainer, a, 403);                                           // del sistema

        // Ocultar un logro del sistema y una categoría del sistema solo para este profesional
        assertTrue(send("PATCH", "/api/library/achievements/" + hito.getId() + "/hidden", trainer, "{\"hidden\":true}", 200).get("hidden").asBoolean());
        send("PUT", "/api/library/achievements/categories/" + sys.getId(), trainer, "{\"name\":\"x\",\"visibility\":\"TAGS\",\"tagIds\":[]}", 400);
        send("PUT", "/api/library/achievements/categories/" + sys.getId(), trainer, "{\"name\":\"x\",\"visibility\":\"HIDDEN\"}", 200);
        String mine = send("GET", "/api/library/achievements", trainer, null, 200).toString();
        String theirs = send("GET", "/api/library/achievements", otherTrainer, null, 200).toString();
        assertTrue(mine.contains("\"hidden\":true"));
        assertFalse(theirs.contains("100 kg en sentadilla"));
        for (JsonNode c : json.readTree(theirs)) if (c.get("id").asLong() == sys.getId()) assertEquals("ALL", c.get("visibility").asText());

        // Categoría propia con logros: borrarla borra sus logros
        long cat = send("POST", "/api/library/achievements/categories", trainer, "{\"name\":\"Retos del mes\",\"visibility\":\"ALL\"}", 201).get("id").asLong();
        send("POST", "/api/library/achievements", trainer, a.replace("\"categoryId\":" + personalId, "\"categoryId\":" + cat), 201);
        send("DELETE", "/api/library/achievements/categories/" + sys.getId(), trainer, null, 403);
        send("DELETE", "/api/library/achievements/categories/" + cat, otherTrainer, null, 403);
        send("DELETE", "/api/library/achievements/categories/" + cat, trainer, null, 204);
        assertEquals(0, achievements.findByCategoryId(cat).size());
        send("DELETE", "/api/library/achievements/" + achId, trainer, null, 204);
    }

    private TemplatePack pack(String kind, String name) {
        TemplatePack p = new TemplatePack(); p.setKind(kind); p.setName(name); p.setDescription("d"); p.setCover("gym"); p.setPosition(99);
        return packs.save(p);
    }

    private TemplateItem item(TemplatePack p, String name, String detail, String payload) {
        TemplateItem i = new TemplateItem(); i.setPackId(p.getId()); i.setName(name); i.setDescription("d"); i.setDetail(detail);
        i.setPayload(payload); i.setPosition(1);
        return templateItems.save(i);
    }
}
