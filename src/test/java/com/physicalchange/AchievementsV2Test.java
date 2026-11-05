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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logros v2 (T262, docs/api-logros-v2.md): sincronización profesional ↔ cliente, puntos y emoji, "Próximamente"
 * y las métricas nuevas del catálogo (en los tests Flyway no siembra el catálogo: se crean aquí).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AchievementsV2Test {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired ExerciseLogRepository logs;
    @Autowired WorkoutRepository workouts;
    @Autowired WorkoutAssignmentRepository assignments;
    @Autowired ProgramAssignmentRepository programs;
    @Autowired ClientDailyLogRepository diary;
    @Autowired ChatMessageRepository chat;
    @Autowired ProgressEntryRepository progress;
    @Autowired AchievementCategoryRepository categories;
    @Autowired AchievementRepository achievements;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    /** Logros del sistema de prueba por métrica (una vez por clase). */
    static final Map<String, Long> SYS = new LinkedHashMap<>();
    static Long soonId, sysCategory;

    String sfx, trainer, otherTrainer, client;
    Long trainerId, clientId, freshClientId;
    final LocalDate today = LocalDate.now();

    @BeforeEach void data() {
        if (SYS.isEmpty()) {
            AchievementCategory c = new AchievementCategory();
            c.setSystemKey("T262_" + UUID.randomUUID().toString().substring(0, 8));
            c.setName("Logros v2 (test)");
            c.setEmoji("🧪");
            c = categories.save(c);
            sysCategory = c.getId();
            sys(c, "SESSION_MUSCLE_GROUPS", 5); sys(c, "SESSION_BIG_GROUPS", 3); sys(c, "SESSION_CORE_EXERCISES", 3);
            sys(c, "FULL_BODY_SESSION", 500); sys(c, "PROGRAMS_COMPLETED", 1); sys(c, "WEEK_WORKOUTS", 3);
            sys(c, "DOUBLE_SESSION", 1); sys(c, "WEEKDAY_STREAK_MON", 2); sys(c, "COMEBACK", 14);
            sys(c, "CONSECUTIVE_WORKOUTS", 3); sys(c, "DROP_SETS", 1); sys(c, "WEIGHT_LOGGED", 1);
            sys(c, "DIARY_STREAK", 3); sys(c, "DIARY_WEEKS", 1); sys(c, "SLEEP_NIGHT_MIN", 480); sys(c, "SLEEP_STREAK_7H", 2);
            sys(c, "SHORT_SLEEP_LOGGED", 1); sys(c, "MOOD_LOW_STREAK", 3); sys(c, "MOOD_COMEBACK", 3); sys(c, "MESSAGES_SENT", 1);
            SYS.put("MESSAGES_SENT_10", saveSystem(c, "MESSAGES_SENT", 10, false, "💬"));
            soonId = saveSystem(c, null, null, true, null);
        }
        sfx = UUID.randomUUID().toString().substring(0, 8);
        AppUser t = save("v2t-" + sfx + "@test", Role.TRAINER, null);
        trainerId = t.getId();
        clientId = save("v2c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        freshClientId = save("v2f-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        trainer = token(t.getId(), Role.TRAINER);
        otherTrainer = token(save("v2o-" + sfx + "@test", Role.TRAINER, null).getId(), Role.TRAINER);
        client = token(clientId, Role.CLIENT);
    }

    // ---------- Sincronización, puntos y emoji ----------

    @Test void loCreadoEnLaCategoriaPersonalLlegaAlClienteConSusPuntosYEmoji() throws Exception {
        JsonNode cats = send("GET", "/api/library/achievements", trainer, null, 200);
        JsonNode personal = StreamUtil.first(cats, c -> "Logros personalizados".equals(c.get("name").asText()));
        assertEquals("ALL", personal.get("visibility").asText());
        assertTrue(personal.get("clientVisible").asBoolean());
        assertEquals("⭐", personal.get("emoji").asText());
        long cat = personal.get("id").asLong();

        send("POST", "/api/library/achievements", trainer, ach(cat, "Cero", 0, "🔥"), 400);
        send("POST", "/api/library/achievements", trainer, ach(cat, "Mil uno", 1001, "🔥"), 400);
        send("POST", "/api/library/achievements", trainer, ach(cat, "Letras", 10, "abc"), 400);
        JsonNode created = send("POST", "/api/library/achievements", trainer, ach(cat, "Racha de dos " + sfx, 25, "🔥"), 201);
        assertEquals(25, created.get("points").asInt());
        assertEquals("🔥", created.get("emoji").asText());
        assertTrue(created.get("clientVisible").asBoolean());
        long id = created.get("id").asLong();

        JsonNode mine = find(send("GET", "/api/my/achievements", client, null, 200), id);
        assertNotNull(mine, "el logro nuevo del profesional tiene que llegar al cliente");
        assertEquals(25, mine.get("points").asInt());
        assertEquals("🔥", mine.get("emoji").asText());
        assertEquals(cat, mine.get("categoryId").asLong());

        // Cambiar los puntos se refleja en el cliente.
        send("PUT", "/api/library/achievements/" + id, trainer, ach(cat, "Racha de dos " + sfx, 40, "🔥"), 200);
        assertEquals(40, find(send("GET", "/api/my/achievements", client, null, 200), id).get("points").asInt());

        // Oculto por el profesional → desaparece para el cliente; se avisa en su vista.
        JsonNode hid = send("PATCH", "/api/library/achievements/" + id + "/hidden", trainer, "{\"hidden\":true}", 200);
        assertFalse(hid.get("clientVisible").asBoolean());
        assertNull(find(send("GET", "/api/my/achievements", client, null, 200), id));
    }

    @Test void seccionNuevaConEmojiLlegaAlClienteYOcultaNo() throws Exception {
        long visible = send("POST", "/api/library/achievements/categories", trainer,
                "{\"name\":\"Retos " + sfx + "\",\"visibility\":\"ALL\",\"tagIds\":[],\"emoji\":\"🎯\"}", 201).get("id").asLong();
        long hiddenCat = send("POST", "/api/library/achievements/categories", trainer,
                "{\"name\":\"Ocultos " + sfx + "\",\"visibility\":\"HIDDEN\",\"tagIds\":[]}", 201).get("id").asLong();
        send("POST", "/api/library/achievements/categories", trainer, "{\"name\":\"X\",\"visibility\":\"ALL\",\"tagIds\":[],\"emoji\":\"x1\"}", 400);
        long a = send("POST", "/api/library/achievements", trainer, ach(visible, "Sin emoji " + sfx, 15, null), 201).get("id").asLong();
        long b = send("POST", "/api/library/achievements", trainer, ach(hiddenCat, "En oculta " + sfx, 15, "🙈"), 201).get("id").asLong();

        JsonNode list = send("GET", "/api/library/achievements", trainer, null, 200);
        assertFalse(StreamUtil.first(list, c -> c.get("id").asLong() == hiddenCat).get("clientVisible").asBoolean());

        JsonNode mine = send("GET", "/api/my/achievements", client, null, 200);
        JsonNode got = find(mine, a);
        assertEquals("🎯", got.get("emoji").asText(), "sin emoji propio usa el de su sección");
        assertNull(find(mine, b), "una sección oculta no llega al cliente");
        JsonNode section = StreamUtil.first(mine.get("categories"), c -> c.get("id").asLong() == visible);
        assertEquals("🎯", section.get("emoji").asText());
        assertEquals(1, section.get("totalCount").asInt());
        // Otro profesional no ve ni toca la sección.
        send("PUT", "/api/library/achievements/categories/" + visible, otherTrainer, "{\"name\":\"Mía\",\"visibility\":\"ALL\",\"tagIds\":[]}", 403);
    }

    @Test void proximamenteNoSeConsigueNiCuentaEnElTotal() throws Exception {
        JsonNode mine = send("GET", "/api/my/achievements", client, null, 200);
        JsonNode soon = find(mine, soonId);
        assertEquals("COMING_SOON", soon.get("status").asText());
        assertTrue(soon.get("progress").isNull());
        assertTrue(soon.get("percent").isNull());
        assertTrue(mine.get("comingSoonCount").asInt() >= 1);
        JsonNode section = StreamUtil.first(mine.get("categories"), c -> c.get("id").asLong() == sysCategory);
        assertEquals(1, section.get("comingSoonCount").asInt());
        assertEquals(section.get("achievements").size() - 1, section.get("totalCount").asInt());
        int achievable = 0, all = 0;
        for (JsonNode c : mine.get("categories")) for (JsonNode x : c.get("achievements")) { all++; if (!"COMING_SOON".equals(x.get("status").asText())) achievable++; }
        assertEquals(achievable, mine.get("totalCount").asInt());
        assertTrue(all > achievable);
    }

    // ---------- Métricas nuevas ----------

    @Test void metricasNuevasSeDesbloqueanConLosDatosDeLaApp() throws Exception {
        seedActivity(clientId);
        JsonNode mine = send("GET", "/api/clients/" + clientId + "/achievements", trainer, null, 200);
        for (var e : SYS.entrySet()) {
            if (e.getKey().equals("MESSAGES_SENT_10")) continue;
            assertEquals("UNLOCKED", find(mine, e.getValue()).get("status").asText(), e.getKey());
        }
        JsonNode ten = find(mine, SYS.get("MESSAGES_SENT_10"));
        assertEquals("LOCKED", ten.get("status").asText());
        assertEquals(1, ten.get("progress").asInt());
        assertEquals(10, ten.get("target").asInt());
        assertEquals("💬", ten.get("emoji").asText());
        assertEquals("🧪", find(mine, SYS.get("DROP_SETS")).get("emoji").asText(), "sin emoji propio usa el de su sección");

        // Un cliente sin actividad no consigue ninguno (y los de sí/no no muestran progreso).
        JsonNode fresh = send("GET", "/api/clients/" + freshClientId + "/achievements", trainer, null, 200);
        for (var e : SYS.entrySet()) assertNotEquals("UNLOCKED", find(fresh, e.getValue()).get("status").asText(), e.getKey());
        assertTrue(find(fresh, SYS.get("COMEBACK")).get("progress").isNull());
    }

    @Test void unWorkoutPasadoSinCompletarRompeLaRacha() throws Exception {
        long w = workout();
        LocalDate d = today.minusDays(10);
        assign(clientId, w, d, d, null);
        assign(clientId, w, d.plusDays(1), d.plusDays(1), null);
        assign(clientId, w, d.plusDays(2), null, null);             // vencido sin completar
        assign(clientId, w, d.plusDays(3), d.plusDays(3), null);
        assign(clientId, w, today.plusDays(2), null, null);          // futuro: no rompe
        JsonNode r = find(send("GET", "/api/clients/" + clientId + "/achievements", trainer, null, 200), SYS.get("CONSECUTIVE_WORKOUTS"));
        assertEquals("LOCKED", r.get("status").asText());
        assertEquals(2, r.get("progress").asInt());
    }

    // ---------- util ----------

    private void seedActivity(Long c) {
        long pecho = ex("Pecho"), espalda = ex("Espalda"), cuad = ex("Cuádriceps"), abd1 = ex("Abdomen"), abd2 = ex("Abdomen"), core = ex("Core");
        LocalDate day = today.minusDays(3);
        set(c, pecho, day, 10, "20", "NORMAL"); set(c, espalda, day, 10, "20", "NORMAL"); set(c, cuad, day, 10, "20", "DROP");
        set(c, abd1, day, 10, "0", "NORMAL"); set(c, abd2, day, 10, "0", "NORMAL"); set(c, core, day, 10, "0", "NORMAL");
        set(c, pecho, today.minusDays(60), 5, "10", "NORMAL");                         // vuelta tras > 14 días
        LocalDate mon = today.with(DayOfWeek.MONDAY).minusWeeks(3);
        set(c, pecho, mon.plusWeeks(1), 5, "10", "NORMAL");                             // 2.º lunes seguido
        long w = workout();
        ProgramAssignment pa = new ProgramAssignment();
        pa.setClientId(c); pa.setProgramName("P"); pa.setStartDate(mon); pa.setEndDate(mon.plusDays(6)); pa.setOrigin("MANUAL");
        pa = programs.save(pa);
        assign(c, w, mon, mon, pa.getId());
        assign(c, w, mon.plusDays(1), mon.plusDays(1), pa.getId());
        assign(c, w, mon.plusDays(1), mon.plusDays(1), null);                           // doble sesión
        assign(c, w, mon.plusDays(2), mon.plusDays(2), null);
        int[] moods = {1, 1, 1, 5, 5, 5};
        for (int i = 0; i < moods.length; i++) {
            ClientDailyLog d = new ClientDailyLog();
            d.setClientId(c); d.setLogDate(today.minusDays(6 - i)); d.setMood(moods[i]);
            d.setSleepMinutes(i == 0 ? 280 : i < 3 ? 430 : 500);
            d.setCreatedAt(java.time.LocalDateTime.now()); d.setUpdatedAt(java.time.LocalDateTime.now());
            diary.save(d);
        }
        ChatMessage m = new ChatMessage(); m.setSenderId(c); m.setRecipientId(trainerId); m.setBody("Hola"); chat.save(m);
        ProgressEntry p = new ProgressEntry(); p.setClientId(c); p.setEntryDate(today.minusDays(1)); p.setWeightKg(new BigDecimal("80")); progress.save(p);
    }

    private long ex(String group) {
        Exercise e = new Exercise(); e.setName(group + " " + UUID.randomUUID()); e.setMuscleGroup(group); return exercises.save(e).getId();
    }
    private void set(Long c, long exercise, LocalDate day, int reps, String kg, String type) {
        ExerciseLog l = new ExerciseLog();
        l.setClientId(c); l.setExerciseId(exercise); l.setLogDate(day); l.setSetNumber(1); l.setReps(reps); l.setWeightKg(new BigDecimal(kg)); l.setSetType(type);
        logs.save(l);
    }
    private long workout() { Workout w = new Workout(); w.setTrainerId(trainerId); w.setName("W " + sfx); return workouts.save(w).getId(); }
    private void assign(Long c, long workout, LocalDate scheduled, LocalDate completed, Long program) {
        WorkoutAssignment a = new WorkoutAssignment();
        a.setWorkoutId(workout); a.setClientId(c); a.setScheduledDate(scheduled); a.setCompletedDate(completed); a.setProgramAssignmentId(program);
        assignments.save(a);
    }

    private void sys(AchievementCategory c, String metric, int threshold) { SYS.put(metric, saveSystem(c, metric, threshold, false, null)); }
    private Long saveSystem(AchievementCategory c, String metric, Integer threshold, boolean soon, String emoji) {
        Achievement a = new Achievement();
        a.setCategoryId(c.getId()); a.setName(soon ? "Próximamente" : metric + " " + threshold); a.setConditionType("SYSTEM"); a.setTier("BRONZE");
        a.setMetric(metric); a.setThreshold(threshold == null ? null : BigDecimal.valueOf(threshold)); a.setPoints(5);
        a.setComingSoon(soon); a.setEmoji(emoji);
        return achievements.save(a).getId();
    }

    private static String ach(long category, String name, int points, String emoji) {
        return "{\"categoryId\":" + category + ",\"name\":\"" + name + "\",\"conditionType\":\"STREAK\",\"days\":2,\"durationType\":\"UNLIMITED\","
                + "\"points\":" + points + ",\"emoji\":" + (emoji == null ? "null" : "\"" + emoji + "\"") + "}";
    }

    private static JsonNode find(JsonNode summary, Long id) {
        for (JsonNode c : summary.get("categories")) for (JsonNode a : c.get("achievements")) if (a.get("id").asLong() == id) return a;
        return null;
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }
    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).header("Authorization", auth).characterEncoding(StandardCharsets.UTF_8);
        if (body != null) req.contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8));
        String s = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : json.readTree(s);
    }

    /** Búsqueda en arrays JSON. */
    static final class StreamUtil {
        static JsonNode first(JsonNode array, java.util.function.Predicate<JsonNode> p) {
            for (JsonNode n : array) if (p.test(n)) return n;
            throw new AssertionError("No encontrado");
        }
    }
}
