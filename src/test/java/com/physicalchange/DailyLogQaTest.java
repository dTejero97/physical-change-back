package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.ConflictException;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.DailyLogService;
import com.physicalchange.service.ProgressService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T214: QA and security checks for T201 (client daily log, one weight per day, bookable sessions).
 * Same fixed clock as DailyLogTest: 2026-10-31 00:30 in Madrid.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DailyLogQaTest {
    static final Instant NOW = Instant.parse("2026-10-30T23:30:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    static final int THREADS = 8;

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneId.of("Europe/Madrid")); }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProgressEntryRepository progress;
    @Autowired ClientDailyLogRepository logs;
    @Autowired DailyLogService dailyLog;
    @Autowired ProgressService progressService;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String sfx, admin, trainer, nutritionist, client, client2, orphan;
    AppUser adminUser, trainerUser, nutritionistUser, clientUser, client2User, orphanUser;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        adminUser = save("qa-a-" + sfx + "@test", Role.ADMIN, null);
        trainerUser = save("qa-t-" + sfx + "@test", Role.TRAINER, null);
        nutritionistUser = save("qa-n-" + sfx + "@test", Role.TRAINER, null);
        clientUser = save("qa-c-" + sfx + "@test", Role.CLIENT, trainerUser.getId());
        clientUser.setNutritionistId(nutritionistUser.getId());
        clientUser = users.save(clientUser);
        client2User = save("qa-c2-" + sfx + "@test", Role.CLIENT, trainerUser.getId());
        orphanUser = save("qa-or-" + sfx + "@test", Role.CLIENT, null);   // created by the ADMIN: no trainerId
        admin = token(adminUser); trainer = token(trainerUser); nutritionist = token(nutritionistUser);
        client = token(clientUser); client2 = token(client2User); orphan = token(orphanUser);
    }

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setNutritionEnabled(true); u.setTrainingEnabled(true); u.setPasswordHash(enc.encode("Password1!"));
        return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }
    private static void loginAs(AppUser u) {
        AuthUser a = new AuthUser(u.getId(), u.getEmail(), u.getRole());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(a, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url);
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.contentType("application/json").characterEncoding("UTF-8").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isBlank() ? null : json.readTree(s);
    }

    // ---------- isolation (IDOR) ----------

    /** /api/me ignores any clientId sent in the query or in the body: it always works on the token's client. */
    @Test void meEndpointsIgnoreForeignClientId() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        Long victim = clientUser.getId();
        send("PUT", "/api/me/daily-log/" + day, client, "{\"sleepMinutes\":480,\"mood\":5}", 200);
        send("PUT", "/api/me/daily-log/" + day + "?clientId=" + victim, client2,
                "{\"clientId\":" + victim + ",\"weightKg\":99,\"sleepMinutes\":60,\"mood\":1}", 200);
        JsonNode mine = send("GET", "/api/me/daily-log/" + day, client, null, 200);
        assertEquals(5, mine.get("mood").asInt());
        assertTrue(mine.get("weightKg").isNull());
        assertTrue(progress.findByClientIdAndEntryDate(victim, day).isEmpty());
        JsonNode peek = send("GET", "/api/me/daily-log/" + day + "?clientId=" + victim, client2, null, 200);
        assertEquals(1, peek.get("mood").asInt());   // its own day, not the victim's
        JsonNode month = send("GET", "/api/me/daily-log?month=2026-10&clientId=" + victim, client2, null, 200);
        assertEquals(1, month.get("days").get(29).get("mood").asInt());
    }

    /** A client cannot use the professional's route, not even for itself (role check in SecurityConfig). */
    @Test void clientCannotUseProfessionalRouteEvenForItself() throws Exception {
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log/" + TODAY, client, null, 403);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log?month=2026-10", client, null, 403);
    }

    // ---------- professional permissions (K-02) ----------

    /** Decision T201: the nutritionist who is not the trainer cannot read the diary (same as weight). */
    @Test void nutritionistWhoIsNotTrainerCannotReadDiary() throws Exception {
        send("PUT", "/api/me/daily-log/" + TODAY, client, "{\"mood\":3}", 200);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log/" + TODAY, nutritionist, null, 403);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log?month=2026-10", nutritionist, null, 403);
    }

    /** A former trainer loses access as soon as the client is reassigned. */
    @Test void formerTrainerLosesAccessAfterReassignment() throws Exception {
        send("PUT", "/api/me/daily-log/" + TODAY, client, "{\"mood\":3}", 200);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log/" + TODAY, trainer, null, 200);
        AppUser c = users.findById(clientUser.getId()).orElseThrow();
        c.setTrainerId(nutritionistUser.getId());
        users.save(c);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log/" + TODAY, trainer, null, 403);
        send("GET", "/api/clients/" + clientUser.getId() + "/daily-log/" + TODAY, nutritionist, null, 200);
    }

    /** Contract: "404 si el cliente no existe". ADMIN skips the lookup in assertCanAccessClient. */
    @Test void adminGets404ForUnknownClient() throws Exception {
        send("GET", "/api/clients/999999999/daily-log/" + TODAY, admin, null, 404);
        send("GET", "/api/clients/999999999/daily-log?month=2026-10", admin, null, 404);
    }

    // ---------- one row per day under concurrency ----------

    private <T> List<Object> race(AppUser who, Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                loginAs(who);
                try {
                    start.await();
                    return action.call();
                } catch (Exception ex) {
                    return ex;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    /** Concurrent "save my day" of a new day: never two rows; losers get ConflictException (409), never another error. */
    @Test void concurrentSaveOfSameDayKeepsOneRow() throws Exception {
        LocalDate day = TODAY.minusDays(4);
        List<Object> results = race(clientUser, () -> dailyLog.save(day, new DailyLogRequest(new BigDecimal("72.5"), 420, 4, false)));
        long ok = results.stream().filter(r -> r instanceof DailyLogDto).count();
        for (Object r : results) assertTrue(r instanceof DailyLogDto || r instanceof ConflictException, "unexpected result: " + r);
        assertTrue(ok >= 1);
        assertEquals(1, logs.findByClientIdAndLogDateBetween(clientUser.getId(), day, day).size());
        assertEquals(1, progress.findByClientIdAndEntryDateBetween(clientUser.getId(), day, day).size());
    }

    /** Concurrent POST /api/progress of the same day: one row, losers get ConflictException (409). */
    @Test void concurrentProgressUpsertKeepsOneRow() throws Exception {
        LocalDate day = TODAY.minusDays(5);
        List<Object> results = race(clientUser, () ->
                progressService.add(new ProgressRequest(clientUser.getId(), day, new BigDecimal("70"), null, null)));
        for (Object r : results) assertTrue(r instanceof ProgressDto || r instanceof ConflictException, "unexpected result: " + r);
        assertEquals(1, progress.findByClientIdAndEntryDateBetween(clientUser.getId(), day, day).size());
        Set<Long> ids = new HashSet<>();
        for (Object r : results) if (r instanceof ProgressDto p) ids.add(p.id());
        assertEquals(1, ids.size());   // every winner got the same id
    }

    /** Documents the contract: a PUT with only weightKg clears sleep and mood (the front must send the whole day). */
    @Test void weightOnlyPutClearsSleepAndMoodAsContracted() throws Exception {
        LocalDate day = TODAY.minusDays(6);
        send("PUT", "/api/me/daily-log/" + day, client, "{\"sleepMinutes\":400,\"mood\":4}", 200);
        JsonNode d = send("PUT", "/api/me/daily-log/" + day, client, "{\"weightKg\":71}", 200);
        // Contract: sleep/mood not sent are cleared; the front must always send the whole day.
        assertTrue(d.get("sleepMinutes").isNull());
        assertTrue(d.get("mood").isNull());
        assertTrue(logs.findByClientIdAndLogDate(clientUser.getId(), day).isEmpty());
        assertEquals(0, new BigDecimal("71").compareTo(d.get("weightKg").decimalValue()));
    }

    // ---------- POST /api/progress upsert ----------

    /** Upsert keeps the same id and the trainer cannot create a second entry of the day for the client. */
    @Test void progressUpsertBySeveralAuthorsKeepsOneEntry() throws Exception {
        String body = "{\"clientId\":" + clientUser.getId() + ",\"entryDate\":\"2026-10-12\",\"weightKg\":%s}";
        long a = send("POST", "/api/progress", client, body.formatted("80"), 201).get("id").asLong();
        long b = send("POST", "/api/progress", trainer, body.formatted("81"), 201).get("id").asLong();
        long c = send("POST", "/api/progress", admin, body.formatted("82"), 201).get("id").asLong();
        assertEquals(a, b);
        assertEquals(a, c);
        send("POST", "/api/progress", nutritionist, body.formatted("50"), 403);
        send("POST", "/api/progress", client2, body.formatted("50"), 403);
        assertEquals(0, new BigDecimal("82").compareTo(progress.findById(a).orElseThrow().getWeightKg()));
    }

    // ---------- bookable sessions: ADMIN sessions and isClientOf ----------

    private long adminSession(LocalDate date) throws Exception {
        long type = send("GET", "/api/agenda/session-types", admin, null, 200).get(0).get("id").asLong();
        String body = "{\"kind\":\"SESSION\",\"title\":\"Admin " + sfx + "\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + type +
                ",\"cover\":\"gym\",\"date\":\"" + date + "\",\"startTime\":\"18:00\",\"endTime\":\"19:00\",\"bookable\":\"YES\"}";
        return send("POST", "/api/agenda/events", admin, body, 201).get(0).get("id").asLong();
    }

    /** A client without trainer sees and can book the ADMIN's sessions (contract D17). */
    @Test void clientWithoutTrainerSeesAndBooksAdminSessions() throws Exception {
        LocalDate d = TODAY.plusDays(3);
        long ev = adminSession(d);
        String url = "/api/me/agenda/available?from=" + d + "&to=" + d;
        boolean listed = false;
        for (JsonNode n : send("GET", url, orphan, null, 200)) listed |= n.get("id").asLong() == ev;
        assertTrue(listed);
        send("POST", "/api/agenda/events/" + ev + "/bookings", orphan, "{\"clientId\":" + orphanUser.getId() + "}", 201);
    }

    /**
     * Suspicion from T201: isClientOf returns true for any CLIENT when the owner is an ADMIN, so a client WITH a trainer
     * could book an ADMIN session that /api/me/agenda/available does not show him. Contract: 403 "de otro profesional".
     */
    @Test void clientWithTrainerCannotBookAdminSession() throws Exception {
        LocalDate d = TODAY.plusDays(3);
        long ev = adminSession(d);
        String url = "/api/me/agenda/available?from=" + d + "&to=" + d;
        for (JsonNode n : send("GET", url, client, null, 200)) assertNotEquals(ev, n.get("id").asLong());
        send("POST", "/api/agenda/events/" + ev + "/bookings", client, "{\"clientId\":" + clientUser.getId() + "}", 403);
    }
}
