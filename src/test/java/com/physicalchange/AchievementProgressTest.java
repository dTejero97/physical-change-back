package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.AchievementProgressService;
import com.physicalchange.service.ClientActivityEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 7 (T158): motor de logros. Estadísticas, progreso, desbloqueo y aviso separados; desbloqueos únicos aunque
 * el evento se procese varias veces; editar el perfil no concede logros; sin datos → NO_DATA.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AchievementProgressTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired AchievementCategoryRepository categories;
    @Autowired AchievementRepository achievements;
    @Autowired AchievementUnlockRepository unlocks;
    @Autowired ClientStatsRepository stats;
    @Autowired NotificationRepository notifications;
    @Autowired AchievementProgressService engine;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    /** Logros del sistema con métrica, como los de V13 (en los tests Flyway no siembra el catálogo). */
    static Long firstActivity, ton, reps30, oneWorkout, cardio, streak3;

    String trainer, client, otherClient, otherTrainer;
    Long trainerId, clientId;
    Long squat;

    @BeforeEach void data() {
        if (firstActivity == null) {
            AchievementCategory c = new AchievementCategory();
            c.setSystemKey("T7_" + UUID.randomUUID().toString().substring(0, 8));
            c.setName("Progreso (test)");
            c = categories.save(c);
            firstActivity = system(c, "Empezamos", "FIRST_ACTIVITY", null, 5);
            ton = system(c, "Primera tonelada", "SESSION_VOLUME", 1000, 10);
            reps30 = system(c, "30 repeticiones", "TOTAL_REPS_ALL", 30, 10);
            oneWorkout = system(c, "1 workout", "WORKOUTS", 1, 10);
            cardio = system(c, "5 cardios", "CARDIO", 5, 10);
            streak3 = system(c, "3 días seguidos", "STREAK_DAYS", 3, 10);
        }
        String k = UUID.randomUUID().toString().substring(0, 8);
        AppUser t = save("ap-t-" + k + "@test", Role.TRAINER, null);
        trainerId = t.getId();
        AppUser c = save("ap-c-" + k + "@test", Role.CLIENT, trainerId);
        clientId = c.getId();
        AppUser c2 = save("ap-c2-" + k + "@test", Role.CLIENT, trainerId);
        AppUser t2 = save("ap-t2-" + k + "@test", Role.TRAINER, null);
        trainer = token(t); client = token(c); otherClient = token(c2); otherTrainer = token(t2);
        Exercise e = new Exercise(); e.setName("Sentadilla"); e.setMuscleGroup("Cuádriceps");
        squat = exercises.save(e).getId();
    }

    private Long system(AchievementCategory c, String name, String metric, Integer threshold, int points) {
        Achievement a = new Achievement();
        a.setCategoryId(c.getId()); a.setName(name); a.setConditionType("SYSTEM"); a.setTier("BRONZE");
        a.setMetric(metric); a.setThreshold(threshold == null ? null : BigDecimal.valueOf(threshold)); a.setPoints(points);
        return achievements.save(a).getId();
    }
    private AppUser save(String email, Role role, Long trainer) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).header("Authorization", auth);
        if (body != null) req.contentType("application/json").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : json.readTree(s);
    }

    private long logSet(LocalDate day, int reps, String kg) throws Exception {
        return send("POST", "/api/exercise-logs", client, "{\"exerciseId\":" + squat + ",\"logDate\":\"" + day + "\",\"setNumber\":1,\"reps\":" + reps
                + ",\"weightKg\":" + kg + "}", 201).get("id").asLong();
    }
    private boolean unlocked(Long achievementId) {
        return unlocks.findByClientId(clientId).stream().anyMatch(u -> u.getAchievementId().equals(achievementId));
    }
    private JsonNode find(JsonNode summary, Long achievementId) {
        for (JsonNode c : summary.get("categories")) for (JsonNode a : c.get("achievements")) if (a.get("id").asLong() == achievementId) return a;
        throw new AssertionError("Logro " + achievementId + " no disponible");
    }
    private long achievementNotices() { return notifications.countByUserIdAndType(clientId, "ACHIEVEMENT_UNLOCKED"); }

    @Test void registrarActividadActualizaEstadisticasYDesbloqueaSinConsultar() throws Exception {
        logSet(LocalDate.now(), 10, "100");   // 1.000 kg de volumen en un día
        // Sin pedir la pantalla: el evento tras confirmar la serie ya ha desbloqueado y avisado.
        assertTrue(unlocked(firstActivity));
        assertTrue(unlocked(ton));
        assertFalse(unlocked(reps30));
        ClientStats s = stats.findById(clientId).orElseThrow();
        assertEquals(1, s.getTotalSets());
        assertEquals(10, s.getTotalReps());
        assertEquals(0, new BigDecimal("1000").compareTo(s.getTotalVolumeKg()));
        assertEquals(1, s.getCurrentStreak());
        assertEquals(2, achievementNotices());

        JsonNode sum = send("GET", "/api/my/achievements", client, null, 200);
        assertEquals("LOCKED", find(sum, reps30).get("status").asText());
        assertEquals(10, find(sum, reps30).get("progress").asInt());
        assertEquals(33, find(sum, reps30).get("percent").asInt());
        assertEquals(LocalDate.now().toString(), find(sum, ton).get("unlockedOn").asText());
        assertEquals(15, sum.get("points").asInt());
        assertEquals(1, sum.get("level").asInt());
    }

    @Test void procesarElMismoEventoVariasVecesNoDuplicaLogrosNiAvisos() throws Exception {
        logSet(LocalDate.now(), 10, "100");
        long count = unlocks.countByClientId(clientId), notices = achievementNotices();
        ClientStats before = stats.findById(clientId).orElseThrow();
        engine.onActivity(new ClientActivityEvent(clientId));
        engine.onActivity(new ClientActivityEvent(clientId));
        send("GET", "/api/my/achievements", client, null, 200);
        send("GET", "/api/clients/" + clientId + "/achievements", trainer, null, 200);
        assertEquals(count, unlocks.countByClientId(clientId));
        assertEquals(notices, achievementNotices());
        ClientStats after = stats.findById(clientId).orElseThrow();
        assertEquals(before.getTotalSets(), after.getTotalSets());
        assertEquals(0, before.getTotalVolumeKg().compareTo(after.getTotalVolumeKg()));
    }

    @Test void unaPeticionRechazadaNoCambiaEstadisticasNiLogros() throws Exception {
        logSet(LocalDate.now(), 10, "10");
        long count = unlocks.countByClientId(clientId);
        // Fuera de rango: 400, nada se guarda y nada se evalúa.
        send("POST", "/api/exercise-logs", client, "{\"exerciseId\":" + squat + ",\"logDate\":\"" + LocalDate.now()
                + "\",\"setNumber\":1,\"reps\":900,\"weightKg\":10}", 400);
        assertEquals(1, stats.findById(clientId).orElseThrow().getTotalSets());
        assertEquals(count, unlocks.countByClientId(clientId));
    }

    @Test void unLogroConseguidoNoSePierdeAlBorrarDatos() throws Exception {
        long id = logSet(LocalDate.now(), 10, "100");
        assertTrue(unlocked(ton));
        send("DELETE", "/api/exercise-logs/" + id, client, null, 204);
        assertEquals(0, stats.findById(clientId).orElseThrow().getTotalSets(), "las estadísticas se recalculan");
        JsonNode sum = send("GET", "/api/my/achievements", client, null, 200);
        assertEquals("UNLOCKED", find(sum, ton).get("status").asText());
    }

    @Test void completarUnWorkoutYRachaDeDias() throws Exception {
        long w = send("POST", "/api/workouts", trainer, "{\"name\":\"Full body\"}", 201).get("id").asLong();
        long a = send("POST", "/api/workouts/assign", trainer, "{\"workoutId\":" + w + ",\"clientId\":" + clientId
                + ",\"scheduledDate\":\"" + LocalDate.now() + "\"}", 201).get("id").asLong();
        assertFalse(unlocked(oneWorkout));
        send("POST", "/api/assignments/" + a + "/complete", client, null, 200);
        assertTrue(unlocked(oneWorkout));
        logSet(LocalDate.now().minusDays(2), 5, "20");
        assertFalse(unlocked(streak3));
        logSet(LocalDate.now().minusDays(1), 5, "20");
        assertTrue(unlocked(streak3));
        assertEquals(3, stats.findById(clientId).orElseThrow().getBestStreak());
    }

    @Test void editarElPerfilNoConcedeLogros() throws Exception {
        send("PUT", "/api/clients/" + clientId + "/profile", trainer, "{\"planName\":null,\"planStatus\":\"ACTIVE\",\"assignee\":\"ME\","
                + "\"appAccess\":true,\"heightCm\":180,\"weightKg\":80,\"gender\":\"MALE\",\"birthDate\":\"1990-01-01\","
                + "\"services\":[\"FITNESS\"],\"tagIds\":[]}", 200);
        assertEquals(0, unlocks.countByClientId(clientId));
        assertEquals(0, achievementNotices());
        assertTrue(stats.findById(clientId).isEmpty(), "editar la ficha no dispara la evaluación");
        JsonNode sum = send("GET", "/api/my/achievements", client, null, 200);
        assertEquals("LOCKED", find(sum, firstActivity).get("status").asText());
    }

    @Test void sinDatosParaComprobarNoSeInventaNada() throws Exception {
        logSet(LocalDate.now(), 10, "100");
        JsonNode a = find(send("GET", "/api/my/achievements", client, null, 200), cardio);
        assertEquals("NO_DATA", a.get("status").asText());
        assertTrue(a.get("progress").isNull());
        assertFalse(unlocked(cardio));
    }

    @Test void permisosDeLosLogrosDeUnCliente() throws Exception {
        send("GET", "/api/clients/" + clientId + "/achievements", trainer, null, 200);
        send("GET", "/api/clients/" + clientId + "/achievements", otherTrainer, null, 403);
        send("GET", "/api/clients/" + clientId + "/achievements", otherClient, null, 403);
        send("GET", "/api/my/achievements", trainer, null, 403);
        AppUser admin = users.findByRole(Role.ADMIN).get(0);
        send("GET", "/api/clients/" + clientId + "/achievements", token(admin), null, 200);
    }
}
