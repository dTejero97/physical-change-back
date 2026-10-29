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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 7 (T158): las solicitudes de cambio nunca modifican el plan; solo la aceptación explícita del profesional
 * responsable lo hace, y solo con el cambio aprobado. Permisos comprobados en el backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangeRequestFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired FoodRepository foods;
    @Autowired WorkoutItemRepository items;
    @Autowired WorkoutAssignmentRepository assignments;
    @Autowired NutritionPlanRepository plans;
    @Autowired NotificationRepository notifications;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    /** Datos nuevos por test: la base de datos de tests es compartida. */
    String trainer, client, otherClient, otherTrainer, collaborator;
    Long trainerId, clientId, otherClientId;
    Long squat, press, row;

    @BeforeEach void data() {
        String k = UUID.randomUUID().toString().substring(0, 8);
        AppUser t = save("cr-t-" + k + "@test", Role.TRAINER, null, true);
        trainerId = t.getId();
        AppUser c = save("cr-c-" + k + "@test", Role.CLIENT, trainerId, false);
        clientId = c.getId();
        AppUser c2 = save("cr-c2-" + k + "@test", Role.CLIENT, trainerId, false);
        otherClientId = c2.getId();
        AppUser t2 = save("cr-t2-" + k + "@test", Role.TRAINER, null, true);
        AppUser col = save("cr-col-" + k + "@test", Role.COLLABORATOR, null, false);
        trainer = token(t); client = token(c); otherClient = token(c2); otherTrainer = token(t2); collaborator = token(col);
        squat = exercise("Sentadilla"); press = exercise("Press banca"); row = exercise("Remo");
    }

    private AppUser save(String email, Role role, Long trainer, boolean nutrition) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setNutritionEnabled(nutrition); u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }
    private Long exercise(String name) { Exercise e = new Exercise(); e.setName(name); return exercises.save(e).getId(); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).header("Authorization", auth);
        if (body != null) req.contentType("application/json").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : json.readTree(s);
    }

    /** Rutina de 2 ejercicios (sentadilla 4x10 a 80 kg, press 3x8 a 60 kg) asignada a los clientes indicados. */
    private long[] routine(Long... clients) throws Exception {
        long w = send("POST", "/api/workouts", trainer, "{\"name\":\"Pierna\"}", 201).get("id").asLong();
        send("POST", "/api/workouts/" + w + "/items", trainer, "{\"exerciseId\":" + squat + ",\"sets\":4,\"reps\":10,\"weightKg\":80,\"restSeconds\":120}", 201);
        send("POST", "/api/workouts/" + w + "/items", trainer, "{\"exerciseId\":" + press + ",\"sets\":3,\"reps\":8,\"weightKg\":60,\"restSeconds\":90}", 201);
        long[] out = new long[clients.length + 1];
        out[0] = w;
        for (int i = 0; i < clients.length; i++)
            out[i + 1] = send("POST", "/api/workouts/assign", trainer,
                    "{\"workoutId\":" + w + ",\"clientId\":" + clients[i] + ",\"scheduledDate\":\"" + LocalDate.now().plusDays(1) + "\"}", 201).get("id").asLong();
        return out;
    }

    private String plan(long workoutId) throws Exception { return send("GET", "/api/workouts/" + workoutId, trainer, null, 200).get("items").toString(); }
    private long itemId(long workoutId, int position) { return items.findByWorkoutIdOrderByPositionAsc(workoutId).get(position - 1).getId(); }
    private String modify(long assignment, long item, int reps) {
        return "{\"action\":\"MODIFY_EXERCISE\",\"assignmentId\":" + assignment + ",\"workoutItemId\":" + item + ",\"reps\":" + reps + ",\"reason\":\"molestia\"}";
    }

    // ---------- Entrenamiento ----------

    @Test void unaSolicitudPendienteNoModificaElPlan() throws Exception {
        long[] r = routine(clientId);
        String before = plan(r[0]);
        JsonNode req = send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 6), 201);
        assertEquals("PENDING", req.get("status").asText());
        assertEquals(before, plan(r[0]), "crear la solicitud no toca la rutina");
        assertEquals(r[0], assignments.findById(r[1]).orElseThrow().getWorkoutId(), "la sesión sigue en la rutina original");
        // El cliente sigue viendo y usando la planificación original.
        JsonNode comp = send("GET", "/api/assignments/" + r[1] + "/compliance", client, null, 200);
        assertEquals(10, comp.get("items").get(0).get("plannedReps").asInt());
        // El entrenador recibe el aviso.
        assertEquals(1, notifications.countByUserIdAndType(trainerId, "CHANGE_REQUEST_CREATED"));
    }

    @Test void unRechazoConservaElPlanOriginal() throws Exception {
        long[] r = routine(clientId);
        String before = plan(r[0]);
        long id = send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 6), 201).get("id").asLong();
        JsonNode d = send("POST", "/api/change-requests/" + id + "/reject", trainer, "{\"note\":\"Seguimos con 10\"}", 200);
        assertEquals("REJECTED", d.get("status").asText());
        assertEquals(before, plan(r[0]));
        assertEquals(1, notifications.countByUserIdAndType(clientId, "CHANGE_REQUEST_REJECTED"));
        JsonNode history = send("GET", "/api/change-requests/" + id + "/history", client, null, 200);
        assertEquals("CREATED", history.get(0).get("action").asText());
        assertEquals("REJECTED", history.get(1).get("action").asText());
    }

    @Test void unaAceptacionAplicaSoloElCambioAprobado() throws Exception {
        long[] r = routine(clientId);
        long squatItem = itemId(r[0], 1);
        long id = send("POST", "/api/change-requests/training", client, modify(r[1], squatItem, 6), 201).get("id").asLong();
        JsonNode d = send("POST", "/api/change-requests/" + id + "/accept", trainer, "{}", 200);
        assertEquals("ACCEPTED", d.get("status").asText());
        assertFalse(d.get("copiedWorkout").asBoolean(), "rutina de un solo cliente: se cambia en su sitio");
        WorkoutItem s = items.findById(squatItem).orElseThrow();
        assertEquals(6, s.getReps());
        assertEquals(4, s.getSets(), "las series no se pidieron: no cambian");
        assertEquals(0, new BigDecimal("80").compareTo(s.getWeightKg()));
        WorkoutItem p = items.findByWorkoutIdOrderByPositionAsc(r[0]).get(1);
        assertEquals(8, p.getReps()); assertEquals(3, p.getSets());
        assertEquals(1, notifications.countByUserIdAndType(clientId, "CHANGE_REQUEST_ACCEPTED"));
    }

    @Test void aceptarEnUnaRutinaCompartidaNoCambiaElPlanDeOtrosClientes() throws Exception {
        long[] r = routine(clientId, otherClientId);
        String before = plan(r[0]);
        long id = send("POST", "/api/change-requests/training", client,
                "{\"action\":\"REPLACE_EXERCISE\",\"assignmentId\":" + r[1] + ",\"workoutItemId\":" + itemId(r[0], 2) + ",\"exerciseId\":" + row + "}", 201).get("id").asLong();
        JsonNode d = send("POST", "/api/change-requests/" + id + "/accept", trainer, null, 200);
        assertTrue(d.get("copiedWorkout").asBoolean());
        long copy = d.get("appliedWorkoutId").asLong();
        assertNotEquals(r[0], copy);
        assertEquals(before, plan(r[0]), "la rutina original (del otro cliente) no cambia");
        assertEquals(r[0], assignments.findById(r[2]).orElseThrow().getWorkoutId());
        assertEquals(copy, assignments.findById(r[1]).orElseThrow().getWorkoutId(), "la sesión del cliente pasa a su copia");
        assertEquals(row, items.findByWorkoutIdOrderByPositionAsc(copy).get(1).getExerciseId());
        assertEquals(squat, items.findByWorkoutIdOrderByPositionAsc(copy).get(0).getExerciseId());
    }

    @Test void anadirYQuitarEjercicioSoloAlAceptar() throws Exception {
        long[] r = routine(clientId);
        long add = send("POST", "/api/change-requests/training", client,
                "{\"action\":\"ADD_EXERCISE\",\"assignmentId\":" + r[1] + ",\"exerciseId\":" + row + ",\"sets\":3,\"reps\":12,\"weightKg\":40}", 201).get("id").asLong();
        long remove = send("POST", "/api/change-requests/training", client,
                "{\"action\":\"REMOVE_EXERCISE\",\"assignmentId\":" + r[1] + ",\"workoutItemId\":" + itemId(r[0], 2) + "}", 201).get("id").asLong();
        assertEquals(2, items.countByWorkoutId(r[0]));
        send("POST", "/api/change-requests/" + add + "/accept", trainer, null, 200);
        assertEquals(3, items.countByWorkoutId(r[0]));
        send("POST", "/api/change-requests/" + remove + "/accept", trainer, null, 200);
        var left = items.findByWorkoutIdOrderByPositionAsc(r[0]);
        assertEquals(2, left.size());
        assertEquals(squat, left.get(0).getExerciseId());
        assertEquals(row, left.get(1).getExerciseId());
        assertEquals(2, left.get(1).getPosition(), "posiciones sin huecos");
    }

    @Test void sinDuplicadosYUnaDecisionEsFinal() throws Exception {
        long[] r = routine(clientId);
        String body = modify(r[1], itemId(r[0], 1), 6);
        long id = send("POST", "/api/change-requests/training", client, body, 201).get("id").asLong();
        send("POST", "/api/change-requests/training", client, body, 409);
        send("POST", "/api/change-requests/" + id + "/accept", trainer, null, 200);
        send("POST", "/api/change-requests/" + id + "/reject", trainer, null, 409);
        send("POST", "/api/change-requests/" + id + "/accept", trainer, null, 409);
        // Decidida la anterior, se puede pedir otro cambio sobre el mismo ejercicio.
        send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 5), 201);
    }

    @Test void soloElProfesionalResponsableDecide() throws Exception {
        long[] r = routine(clientId);
        long id = send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 6), 201).get("id").asLong();
        send("POST", "/api/change-requests/" + id + "/accept", client, null, 403);        // el propio solicitante
        send("POST", "/api/change-requests/" + id + "/accept", otherClient, null, 403);
        send("POST", "/api/change-requests/" + id + "/accept", otherTrainer, null, 403);  // otro profesional
        send("POST", "/api/change-requests/" + id + "/reject", otherTrainer, null, 403);
        send("POST", "/api/change-requests/" + id + "/accept", collaborator, null, 403);
        send("GET", "/api/change-requests/" + id + "/history", otherClient, null, 403);
        send("GET", "/api/clients/" + clientId + "/change-requests", otherTrainer, null, 403);
        assertEquals(10, items.findById(itemId(r[0], 1)).orElseThrow().getReps(), "ningún intento no autorizado cambia el plan");
        assertEquals(1, send("GET", "/api/change-requests?status=PENDING", trainer, null, 200).size());
        assertEquals(0, send("GET", "/api/change-requests?status=PENDING", otherTrainer, null, 200).size());
    }

    @Test void elClienteNoModificaDirectamenteSuRutina() throws Exception {
        long[] r = routine(clientId);
        long item = itemId(r[0], 1);
        String before = plan(r[0]);
        String body = "{\"exerciseId\":" + squat + ",\"sets\":1,\"reps\":1,\"weightKg\":1}";
        send("PUT", "/api/workouts/" + r[0] + "/items/" + item, client, body, 403);
        send("POST", "/api/workouts/" + r[0] + "/items", client, body, 403);
        send("DELETE", "/api/workouts/" + r[0] + "/items/" + item, client, null, 403);
        send("PUT", "/api/workouts/" + r[0], client, "{\"name\":\"Mía\"}", 403);
        send("POST", "/api/workouts/assign", client, "{\"workoutId\":" + r[0] + ",\"clientId\":" + clientId + ",\"scheduledDate\":\"" + LocalDate.now() + "\"}", 403);
        assertEquals(before, plan(r[0]));
    }

    @Test void unClienteNoPideCambiosSobreLaSesionDeOtro() throws Exception {
        long[] r = routine(otherClientId);
        send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 6), 403);
        send("POST", "/api/change-requests/training", trainer, modify(r[1], itemId(r[0], 1), 6), 403);
    }

    // ---------- Nutrición ----------

    private long nutritionPlan() throws Exception {
        return send("POST", "/api/nutrition/plans", trainer,
                "{\"clientId\":" + clientId + ",\"name\":\"Volumen\",\"dailyKcal\":2500,\"targetProteinG\":150,\"targetCarbsG\":300,\"targetFatG\":70}", 201).get("id").asLong();
    }

    @Test void elClienteNoModificaSusObjetivosNutricionales() throws Exception {
        long p = nutritionPlan();
        send("PUT", "/api/nutrition/plans/" + p, client, "{\"name\":\"Volumen\",\"dailyKcal\":4000,\"targetProteinG\":300}", 403);
        Food f = new Food(); f.setName("Arroz"); f.setKcal100g(new BigDecimal("130"));
        long food = foods.save(f).getId();
        send("POST", "/api/nutrition/plans/" + p + "/meals", client, "{\"mealName\":\"Cena\",\"foodId\":" + food + ",\"grams\":100}", 403);
        NutritionPlan saved = plans.findById(p).orElseThrow();
        assertEquals(2500, saved.getDailyKcal());
        assertEquals(1, saved.getVersion());
        JsonNode mine = send("GET", "/api/nutrition/plans/client/" + clientId, client, null, 200).get(0);
        assertTrue(mine.get("current").asBoolean());
        assertEquals(0, new BigDecimal("150").compareTo(mine.get("targetProteinG").decimalValue()));
    }

    @Test void solicitudNutricionalPendienteYAceptadaSoloConLoAprobado() throws Exception {
        long p = nutritionPlan();
        long id = send("POST", "/api/change-requests/nutrition", client,
                "{\"action\":\"CHANGE_TARGETS\",\"planId\":" + p + ",\"proteinG\":170}", 201).get("id").asLong();
        assertEquals(0, new BigDecimal("150").compareTo(plans.findById(p).orElseThrow().getTargetProteinG()), "pendiente: el plan no cambia");
        send("POST", "/api/change-requests/nutrition", client, "{\"action\":\"CHANGE_TARGETS\",\"planId\":" + p + ",\"kcal\":3000}", 409);
        send("POST", "/api/change-requests/" + id + "/accept", otherTrainer, null, 403);
        send("POST", "/api/change-requests/" + id + "/accept", trainer, "{\"note\":\"ok\"}", 200);
        NutritionPlan after = plans.findById(p).orElseThrow();
        assertEquals(0, new BigDecimal("170").compareTo(after.getTargetProteinG()));
        assertEquals(2500, after.getDailyKcal(), "solo cambia lo aprobado");
        assertEquals(0, new BigDecimal("300").compareTo(after.getTargetCarbsG()));
        assertEquals(2, after.getVersion());
    }

    @Test void elProfesionalEditaElPlanYSubeLaVersion() throws Exception {
        long p = nutritionPlan();
        send("PUT", "/api/nutrition/plans/" + p, otherTrainer, "{\"name\":\"X\",\"dailyKcal\":2000}", 403);
        JsonNode d = send("PUT", "/api/nutrition/plans/" + p, trainer,
                "{\"name\":\"Definición\",\"dailyKcal\":2100,\"targetProteinG\":160,\"targetCarbsG\":200,\"targetFatG\":60}", 200);
        assertEquals(2, d.get("version").asInt());
        assertEquals(1, notifications.countByUserIdAndType(clientId, "PLAN_UPDATED"));
        // Un plan nuevo pasa a ser el vigente: no se piden cambios sobre el anterior.
        nutritionPlan();
        send("POST", "/api/change-requests/nutrition", client, "{\"action\":\"CHANGE_TARGETS\",\"planId\":" + p + ",\"kcal\":2200}", 409);
    }

    // ---------- Notificaciones ----------

    @Test void cadaUsuarioVeYMarcaSoloSusNotificaciones() throws Exception {
        long[] r = routine(clientId);
        send("POST", "/api/change-requests/training", client, modify(r[1], itemId(r[0], 1), 6), 201);
        assertEquals(1, send("GET", "/api/notifications/unread-count", trainer, null, 200).get("count").asLong());
        long nid = send("GET", "/api/notifications", trainer, null, 200).get(0).get("id").asLong();
        send("POST", "/api/notifications/" + nid + "/read", otherTrainer, null, 404);
        send("POST", "/api/notifications/" + nid + "/read", trainer, null, 204);
        assertEquals(0, send("GET", "/api/notifications/unread-count", trainer, null, 200).get("count").asLong());
    }
}
