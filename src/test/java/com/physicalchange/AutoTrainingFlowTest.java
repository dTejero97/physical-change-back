package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.mail.MailMessage;
import com.physicalchange.mail.MailSender;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.ProgramAssignmentService;
import com.physicalchange.service.SubscriptionStartedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Entrenamiento automático al contratar (T250, docs/api-entrenamiento-automatico.md v0.1.0). */
@SpringBootTest
@AutoConfigureMockMvc
class AutoTrainingFlowTest {
    static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CaptureMail {
        @Bean @Primary MailSender capture() { return SENT::add; }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired WorkoutRepository workouts;
    @Autowired WorkoutAssignmentRepository assignments;
    @Autowired ProgramAssignmentRepository records;
    @Autowired ProgramAssignmentService service;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private final LocalDate today = LocalDate.now(), tomorrow = LocalDate.now().plusDays(1);
    private String sfx, trainer, otherTrainer, client;
    private Long trainerId, otherTrainerId, clientId, otherClientId;
    private String trainerEmail;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerEmail = "t-" + sfx + "@test";
        trainerId = save(trainerEmail, Role.TRAINER, null).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
    }

    @Test void planConProgramaLoAsignaSoloAlContratarYAvisaUnaVez() throws Exception {
        long w1 = workout(trainerId, "Pierna"), w2 = workout(trainerId, "Torso");
        long program = program(trainer, 2, "[" + item(1, 1, "WORKOUT", w1) + "," + item(1, 3, "WORKOUT", w2) + "," + item(2, 1, "WORKOUT", w1)
                + "," + item(1, 2, "CARDIO", 7L) + "," + item(2, 3, "NOTE", null) + "]");
        long foreignProgram = program(otherTrainer, 1, "[" + item(1, 1, "WORKOUT", workout(otherTrainerId, "Ajeno")) + "]");

        send("POST", "/api/subscription-plans", trainer, planBody("Con ajeno", foreignProgram), 404);
        JsonNode plan = send("POST", "/api/subscription-plans", trainer, planBody("Fuerza " + sfx, program), 201);
        assertEquals(program, plan.get("programId").asLong());
        assertEquals("Programa " + sfx, plan.get("programName").asText());

        long sub = subscribe(plan.get("id").asLong());

        ProgramAssignment pa = records.findByClientIdOrderByCreatedAtDesc(clientId).get(0);
        assertEquals("AUTO", pa.getOrigin());
        assertEquals(sub, pa.getSubscriptionId());
        assertNull(pa.getCreatedBy());
        assertEquals(tomorrow, pa.getStartDate());
        assertEquals(tomorrow.plusDays(13), pa.getEndDate());
        List<LocalDate> dates = assignments.findByClientId(clientId).stream().map(WorkoutAssignment::getScheduledDate).sorted().toList();
        assertEquals(List.of(tomorrow, tomorrow.plusDays(2), tomorrow.plusDays(7)), dates);   // solo WORKOUT; CARDIO y NOTE no

        JsonNode list = send("GET", "/api/clients/" + clientId + "/program-assignments", trainer, null, 200);
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).get("workouts").asInt());

        JsonNode n = notification(trainer);
        assertNotNull(n);
        assertTrue(n.get("title").asText().startsWith("Nuevo cliente:"));
        assertTrue(n.get("body").asText().contains("asignado desde"), n.get("body").asText());
        assertEquals("CLIENT", n.get("refType").asText());
        assertEquals(clientId, n.get("refId").asLong());
        MailMessage mail = SENT.stream().filter(m -> m.to().equals(trainerEmail)).reduce((a, b) -> b).orElseThrow();
        assertTrue(mail.subject().startsWith("Nuevo cliente:"));
        assertTrue(mail.text().contains("http://localhost:4200/clientes"));

        assertFalse(contains(send("GET", "/api/clients/paid-without-training", trainer, null, 200), clientId));

        // El evento repetido (reintento, renovación) no asigna ni avisa otra vez.
        int mails = (int) SENT.stream().filter(m -> m.to().equals(trainerEmail)).count();
        service.onSubscriptionStarted(new SubscriptionStartedEvent(sub));
        assertEquals(1, records.findByClientIdOrderByCreatedAtDesc(clientId).size());
        assertEquals(3, assignments.findByClientId(clientId).size());
        assertEquals(mails, SENT.stream().filter(m -> m.to().equals(trainerEmail)).count());
    }

    @Test void planSinProgramaApareceEnPagadosSinEntrenamientoHastaQueSeLeAsignaUno() throws Exception {
        long plan = send("POST", "/api/subscription-plans", trainer, planBody("Sin programa " + sfx, null), 201).get("id").asLong();
        subscribe(plan);
        assertTrue(notification(trainer).get("body").asText().contains("Aún no tiene entrenamiento"));

        JsonNode row = find(send("GET", "/api/clients/paid-without-training", trainer, null, 200), clientId);
        assertNotNull(row);
        assertEquals("NO_PROGRAM", row.get("reason").asText());
        assertEquals("ACTIVE", row.get("subscriptionStatus").asText());
        assertTrue(row.get("lastTrainingDate").isNull());
        assertFalse(contains(send("GET", "/api/clients/paid-without-training", otherTrainer, null, 200), clientId));
        send("GET", "/api/clients/paid-without-training", client, null, 403);

        long w = workout(trainerId, "Full body");
        long program = program(trainer, 1, "[" + item(1, 1, "WORKOUT", w) + "," + item(1, 4, "WORKOUT", w) + "]");
        long notesOnly = program(trainer, 1, "[" + item(1, 1, "NOTE", null) + "]");
        long foreign = program(otherTrainer, 1, "[" + item(1, 1, "WORKOUT", workout(otherTrainerId, "Ajeno")) + "]");
        String url = "/api/clients/" + clientId + "/program-assignments";
        send("POST", url, trainer, assignBody(program, today.minusDays(1)), 400);   // pasado
        send("POST", url, trainer, assignBody(foreign, today), 404);                 // programa ajeno
        send("POST", url, trainer, assignBody(notesOnly, today), 409);               // sin workouts
        send("POST", "/api/clients/" + otherClientId + "/program-assignments", trainer, assignBody(program, today), 403);   // cliente ajeno
        send("POST", "/api/clients/999999999/program-assignments", trainer, assignBody(program, today), 404);
        send("POST", url, client, assignBody(program, today), 403);
        send("GET", url, otherTrainer, null, 403);

        JsonNode a = send("POST", url, trainer, assignBody(program, today), 201);
        assertEquals("MANUAL", a.get("origin").asText());
        assertEquals(2, a.get("workouts").asInt());
        assertEquals(0, a.get("skipped").asInt());
        assertTrue(a.get("subscriptionId").isNull());
        assertEquals(today.plusDays(3).toString(), assignments.findByClientId(clientId).stream()
                .map(WorkoutAssignment::getScheduledDate).max(Comparator.naturalOrder()).orElseThrow().toString());
        assertFalse(contains(send("GET", "/api/clients/paid-without-training", trainer, null, 200), clientId));
    }

    @Test void siLaAsignacionFallaElAltaSeMantieneYSeAvisa() throws Exception {
        long notesOnly = program(trainer, 1, "[" + item(1, 1, "NOTE", null) + "]");
        long plan = send("POST", "/api/subscription-plans", trainer, planBody("Fallo " + sfx, notesOnly), 201).get("id").asLong();
        long sub = subscribe(plan);   // 201: el alta no se deshace
        assertTrue(records.findByClientIdOrderByCreatedAtDesc(clientId).isEmpty());
        assertTrue(notification(trainer).get("body").asText().contains("No se ha podido asignar"));
        JsonNode row = find(send("GET", "/api/clients/paid-without-training", trainer, null, 200), clientId);
        assertEquals("ASSIGN_FAILED", row.get("reason").asText());
        assertEquals(sub, row.get("subscriptionId").asLong());
    }

    @Test void programaTerminadoVuelveAPagadosSinEntrenamiento() throws Exception {
        WorkoutAssignment old = new WorkoutAssignment();
        old.setWorkoutId(workout(trainerId, "Antiguo"));
        old.setClientId(clientId);
        old.setScheduledDate(today.minusDays(10));
        assignments.save(old);
        long plan = send("POST", "/api/subscription-plans", trainer, planBody("Terminado " + sfx, null), 201).get("id").asLong();
        subscribe(plan);
        JsonNode row = find(send("GET", "/api/clients/paid-without-training", trainer, null, 200), clientId);
        assertEquals("PROGRAM_FINISHED", row.get("reason").asText());
        assertEquals(today.minusDays(10).toString(), row.get("lastTrainingDate").asText());
    }

    // ---------- util ----------

    private long subscribe(long plan) throws Exception {
        String body = "{\"clientId\":" + clientId + ",\"planId\":" + plan + ",\"startDate\":\"" + today + "\"}";
        return send("POST", "/api/subscriptions", trainer, body, 201).get("id").asLong();
    }

    private long workout(Long owner, String name) {
        Workout w = new Workout(); w.setTrainerId(owner); w.setName(name + " " + sfx); return workouts.save(w).getId();
    }

    private long program(String auth, int weeks, String items) throws Exception {
        String body = "{\"name\":\"Programa " + sfx + "\",\"description\":\"\",\"weeks\":" + weeks + ",\"archiveClient\":false,\"items\":" + items + "}";
        JsonNode r = send("POST", "/api/library/programs", auth, body, 201);
        return (r.has("program") ? r.get("program") : r).get("id").asLong();
    }

    private static String item(int week, int day, String kind, Long ref) {
        return "{\"week\":" + week + ",\"day\":" + day + ",\"kind\":\"" + kind + "\",\"refId\":" + ref + ",\"title\":\"" + kind + " " + week + "-" + day + "\"}";
    }

    private static String planBody(String name, Long programId) {
        return "{\"name\":\"" + name + "\",\"description\":\"Descripción\",\"price\":49,\"taxRate\":21,\"cover\":\"brand\",\"bonuses\":[],"
                + "\"autoTagIds\":[],\"frequency\":\"MONTHLY\",\"visibility\":\"ALL\",\"programId\":" + programId + "}";
    }

    private static String assignBody(long programId, LocalDate start) {
        return "{\"programId\":" + programId + ",\"startDate\":\"" + start + "\"}";
    }

    /** El aviso SUBSCRIPTION_STARTED más reciente del usuario. */
    private JsonNode notification(String auth) throws Exception {
        JsonNode list = send("GET", "/api/notifications", auth, null, 200);
        JsonNode items = list.has("content") ? list.get("content") : list;
        return StreamSupport.stream(items.spliterator(), false).filter(n -> "SUBSCRIPTION_STARTED".equals(n.get("type").asText())).findFirst().orElse(null);
    }

    private static JsonNode find(JsonNode list, Long clientId) {
        return StreamSupport.stream(list.spliterator(), false).filter(r -> r.get("clientId").asLong() == clientId).findFirst().orElse(null);
    }

    private static boolean contains(JsonNode list, Long clientId) { return find(list, clientId) != null; }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }
}
