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
import org.springframework.test.web.servlet.MvcResult;
import java.time.LocalDate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Flujo completo: rutina -> asignación -> series del cliente -> cumplimiento -> progreso, pagos, calendario e informe. */
@SpringBootTest
@AutoConfigureMockMvc
class TrainingFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String trainerToken, clientToken;
    private Long trainerId, clientId;

    /** Idempotente: JUnit crea una instancia por método y la base de datos de tests es compartida. */
    @BeforeEach void data() {
        trainerId = users.findByEmailIgnoreCase("tf-t@test").orElseGet(() -> save("tf-t@test", Role.TRAINER, null)).getId();
        clientId = users.findByEmailIgnoreCase("tf-c@test").orElseGet(() -> save("tf-c@test", Role.CLIENT, trainerId)).getId();
        trainerToken = "Bearer " + jwt.generate(new AuthUser(trainerId, "tf-t@test", Role.TRAINER));
        clientToken = "Bearer " + jwt.generate(new AuthUser(clientId, "tf-c@test", Role.CLIENT));
    }

    private AppUser save(String email, Role role, Long trainer) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder req, int status) throws Exception {
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    @Test void flujoEntrenamientoCompleto() throws Exception {
        Exercise press = new Exercise();
        press.setName("Press banca"); press.setMuscleGroup("Pecho");
        Long exerciseId = exercises.save(press).getId();
        LocalDate today = LocalDate.now();

        // Entrenador crea la rutina y configura el ejercicio: 3 series x 10 reps a 60 kg, descanso 90 s
        JsonNode workout = call(post("/api/workouts").header("Authorization", trainerToken)
                .contentType("application/json").content("{\"name\":\"Empuje\",\"notes\":\"lunes\"}"), 201);
        long workoutId = workout.get("id").asLong();
        JsonNode detail = call(post("/api/workouts/" + workoutId + "/items").header("Authorization", trainerToken)
                .contentType("application/json")
                .content("{\"exerciseId\":" + exerciseId + ",\"sets\":3,\"reps\":10,\"weightKg\":60,\"restSeconds\":90}"), 201);
        long itemId = detail.get("items").get(0).get("id").asLong();
        Assertions.assertEquals("Pecho", detail.get("items").get(0).get("muscleGroup").asText());

        // Asignación al cliente
        JsonNode assignment = call(post("/api/workouts/assign").header("Authorization", trainerToken)
                .contentType("application/json")
                .content("{\"workoutId\":" + workoutId + ",\"clientId\":" + clientId + ",\"scheduledDate\":\"" + today + "\"}"), 201);
        long assignmentId = assignment.get("id").asLong();

        // Cliente registra 2 de 3 series
        call(post("/api/exercise-logs").header("Authorization", clientToken).contentType("application/json")
                .content(log(assignmentId, itemId, exerciseId, today, 1, 10, "60")), 201);
        call(post("/api/exercise-logs").header("Authorization", clientToken).contentType("application/json")
                .content(log(assignmentId, itemId, exerciseId, today, 2, 8, "62.5")), 201);

        // Cumplimiento: 2 de 3 series = 67 %, con el peso máximo levantado
        JsonNode compliance = call(get("/api/assignments/" + assignmentId + "/compliance").header("Authorization", clientToken), 200);
        Assertions.assertEquals(67, compliance.get("items").get(0).get("percent").asInt());
        Assertions.assertEquals(0, compliance.get("items").get(0).get("plannedWeightKg").decimalValue().compareTo(new java.math.BigDecimal("60")));
        Assertions.assertEquals(62.5, compliance.get("items").get(0).get("loggedMaxWeightKg").asDouble());

        // Panel de progreso: ejercicio y musculatura
        JsonNode strength = call(get("/api/progress/strength/" + clientId).header("Authorization", clientToken), 200);
        Assertions.assertEquals("Press banca", strength.get("exercises").get(0).get("name").asText());
        Assertions.assertEquals("Pecho", strength.get("muscles").get(0).get("muscleGroup").asText());
        Assertions.assertEquals(2, strength.get("muscles").get(0).get("sets").asInt());

        // Permisos: el cliente no puede crear rutinas
        call(post("/api/workouts").header("Authorization", clientToken).contentType("application/json")
                .content("{\"name\":\"Hack\"}"), 403);

        // Pagos: cobro vencido, marcado como pagado
        JsonNode payment = call(post("/api/payments").header("Authorization", trainerToken).contentType("application/json")
                .content("{\"clientId\":" + clientId + ",\"amount\":50,\"concept\":\"Cuota\",\"dueDate\":\"" + today.minusDays(1) + "\"}"), 201);
        Assertions.assertTrue(payment.get("overdue").asBoolean());
        long paymentId = payment.get("id").asLong();
        call(post("/api/payments/" + paymentId + "/paid").header("Authorization", trainerToken), 200);
        call(get("/api/payments").header("Authorization", clientToken), 200);

        // Calendario: la rutina aparece el día asignado
        JsonNode cal = call(get("/api/calendar?from=" + today + "&to=" + today).header("Authorization", trainerToken), 200);
        Assertions.assertEquals("WORKOUT", cal.get(0).get("type").asText());

        // Informe del entrenador
        JsonNode report = call(get("/api/reports/trainer").header("Authorization", trainerToken), 200);
        Assertions.assertEquals(1, report.get("clients").asInt());
        Assertions.assertEquals(1, report.get("workoutsAssigned").asInt());
        Assertions.assertEquals(0, report.get("overduePayments").asInt());
    }

    @Test void adminEntraConUsuarioAdminYCambiaContrasena() throws Exception {
        call(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin\"}"), 200);
        String adminToken = "Bearer " + jwt.generate(new AuthUser(users.findByEmailIgnoreCase("admin").orElseThrow().getId(), "admin", Role.ADMIN));
        call(put("/api/auth/password").header("Authorization", adminToken).contentType("application/json")
                .content("{\"currentPassword\":\"incorrecta\",\"newPassword\":\"NuevaClave123\"}"), 403);
        call(put("/api/auth/me").header("Authorization", adminToken).contentType("application/json")
                .content("{\"fullName\":\"Admin Gym\"}"), 200);
    }

    private static String log(long assignmentId, long itemId, long exerciseId, LocalDate day, int set, int reps, String kg) {
        return "{\"assignmentId\":" + assignmentId + ",\"workoutItemId\":" + itemId + ",\"exerciseId\":" + exerciseId
                + ",\"logDate\":\"" + day + "\",\"setNumber\":" + set + ",\"reps\":" + reps + ",\"weightKg\":" + kg + "}";
    }
}
