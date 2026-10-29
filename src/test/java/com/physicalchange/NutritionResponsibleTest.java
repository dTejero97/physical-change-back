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
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-013 (T175): cada cliente tiene un único responsable de entrenamiento (su entrenador) y un único responsable
 * de nutrición (su nutricionista asignado por el ADMIN o, si no tiene, su entrenador si ofrece nutrición).
 */
@SpringBootTest
@AutoConfigureMockMvc
class NutritionResponsibleTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String admin, trainer, nutritionist, trainingOnly, client;
    Long trainerId, nutritionistId, trainingOnlyId, clientId;

    @BeforeEach void data() {
        String k = UUID.randomUUID().toString().substring(0, 8);
        admin = token(save("nr-a-" + k + "@test", Role.ADMIN, null, false, true));
        AppUser t = save("nr-t-" + k + "@test", Role.TRAINER, null, true, true);           // entrenador y nutricionista
        AppUser n = save("nr-n-" + k + "@test", Role.TRAINER, null, true, false);          // solo nutricionista
        AppUser o = save("nr-o-" + k + "@test", Role.TRAINER, null, false, true);          // solo entrenador
        AppUser c = save("nr-c-" + k + "@test", Role.CLIENT, t.getId(), false, true);
        trainerId = t.getId(); nutritionistId = n.getId(); trainingOnlyId = o.getId(); clientId = c.getId();
        trainer = token(t); nutritionist = token(n); trainingOnly = token(o); client = token(c);
    }

    private AppUser save(String email, Role role, Long trainer, boolean nutrition, boolean training) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setNutritionEnabled(nutrition); u.setTrainingEnabled(training); u.setPasswordHash(enc.encode("Password1!"));
        return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).header("Authorization", auth);
        if (body != null) req.contentType("application/json").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : json.readTree(s);
    }

    private String planBody() { return "{\"clientId\":" + clientId + ",\"name\":\"Plan\",\"dailyKcal\":2200,\"targetProteinG\":140}"; }
    private void assign(Long pro) throws Exception {
        send("PUT", "/api/clients/" + clientId + "/nutritionist", admin, "{\"nutritionistId\":" + pro + "}", 200);
    }
    private boolean contains(JsonNode list, Long id) { for (JsonNode n : list) if (n.get("id").asLong() == id) return true; return false; }

    @Test void sinNutricionistaLoLlevaSuEntrenador() throws Exception {
        long p = send("POST", "/api/nutrition/plans", trainer, planBody(), 201).get("id").asLong();
        send("POST", "/api/nutrition/plans", nutritionist, planBody(), 403);
        send("PUT", "/api/nutrition/plans/" + p, nutritionist, "{\"name\":\"X\",\"dailyKcal\":1500}", 403);
        send("GET", "/api/nutrition/plans/client/" + clientId, nutritionist, null, 403);
        assertTrue(contains(send("GET", "/api/nutrition/clients", trainer, null, 200), clientId));
        assertFalse(contains(send("GET", "/api/nutrition/clients", nutritionist, null, 200), clientId));
    }

    @Test void conNutricionistaSoloElLlevaLaNutricion() throws Exception {
        long p = send("POST", "/api/nutrition/plans", trainer, planBody(), 201).get("id").asLong();
        assign(nutritionistId);
        // El nutricionista: crea, edita y ve. Es su único responsable de nutrición.
        send("PUT", "/api/nutrition/plans/" + p, nutritionist, "{\"name\":\"Definición\",\"dailyKcal\":2000}", 200);
        send("POST", "/api/nutrition/plans", nutritionist, planBody(), 201);
        assertTrue(contains(send("GET", "/api/nutrition/clients", nutritionist, null, 200), clientId));
        // El entrenador ya no la cambia, aunque escribiera el plan; sí la puede ver.
        send("PUT", "/api/nutrition/plans/" + p, trainer, "{\"name\":\"X\",\"dailyKcal\":1500}", 403);
        send("POST", "/api/nutrition/plans", trainer, planBody(), 403);
        assertEquals(2, send("GET", "/api/nutrition/plans/client/" + clientId, trainer, null, 200).size());
        assertFalse(contains(send("GET", "/api/nutrition/clients", trainer, null, 200), clientId));
        // El nutricionista no ve el resto del cliente.
        send("GET", "/api/clients/" + clientId, nutritionist, null, 403);
        send("GET", "/api/clients/" + clientId + "/assignments", nutritionist, null, 403);
        // El cliente ve que tiene nutrición aunque su entrenador no la ofreciera.
        JsonNode me = send("GET", "/api/auth/me", client, null, 200);
        assertTrue(me.get("nutritionEnabled").asBoolean());
        assertTrue(me.get("trainingEnabled").asBoolean());
        assertEquals(nutritionistId, me.get("nutritionistId").asLong());
    }

    @Test void lasSolicitudesVanAlResponsableVigenteYSeReasignan() throws Exception {
        long p = send("POST", "/api/nutrition/plans", trainer, planBody(), 201).get("id").asLong();
        long id = send("POST", "/api/change-requests/nutrition", client,
                "{\"action\":\"CHANGE_TARGETS\",\"planId\":" + p + ",\"kcal\":2400}", 201).get("id").asLong();
        assign(nutritionistId);
        // Pasa al nuevo responsable, con constancia en el historial.
        JsonNode history = send("GET", "/api/change-requests/" + id + "/history", nutritionist, null, 200);
        assertEquals("REASSIGNED", history.get(history.size() - 1).get("action").asText());
        send("POST", "/api/change-requests/" + id + "/accept", trainer, null, 403);
        send("POST", "/api/change-requests/" + id + "/accept", nutritionist, null, 200);
        // Las nuevas también van al nutricionista.
        JsonNode next = send("POST", "/api/change-requests/nutrition", client,
                "{\"action\":\"CHANGE_TARGETS\",\"planId\":" + p + ",\"kcal\":2500}", 201);
        assertEquals(nutritionistId, next.get("professionalId").asLong());
    }

    @Test void soloElAdminAsignaYSoloAQuienOfreceNutricion() throws Exception {
        send("PUT", "/api/clients/" + clientId + "/nutritionist", trainer, "{\"nutritionistId\":" + trainerId + "}", 403);
        send("PUT", "/api/clients/" + clientId + "/nutritionist", client, "{\"nutritionistId\":" + nutritionistId + "}", 403);
        send("PUT", "/api/clients/" + clientId + "/nutritionist", admin, "{\"nutritionistId\":" + trainingOnlyId + "}", 400);
        send("PUT", "/api/clients/" + clientId + "/nutritionist", admin, "{\"nutritionistId\":" + clientId + "}", 400);
        assertNull(users.findById(clientId).orElseThrow().getNutritionistId());
        JsonNode pros = send("GET", "/api/nutrition/professionals", admin, null, 200);
        for (JsonNode p : pros) {
            if (p.get("id").asLong() == nutritionistId) { assertTrue(p.get("nutritionEnabled").asBoolean()); assertFalse(p.get("trainingEnabled").asBoolean()); }
            if (p.get("id").asLong() == trainingOnlyId) assertFalse(p.get("nutritionEnabled").asBoolean());
        }
        assertTrue(contains(pros, nutritionistId));
        send("GET", "/api/nutrition/professionals", trainer, null, 403);
        // Quitarlo devuelve la nutrición a su entrenador.
        assign(nutritionistId);
        send("PUT", "/api/clients/" + clientId + "/nutritionist", admin, "{\"nutritionistId\":null}", 200);
        send("POST", "/api/nutrition/plans", trainer, planBody(), 201);
        send("POST", "/api/nutrition/plans", nutritionist, planBody(), 403);
    }

    @Test void serviciosDelProfesional() throws Exception {
        // Solo nutricionista: no crea rutinas.
        send("POST", "/api/workouts", nutritionist, "{\"name\":\"Pierna\"}", 403);
        send("POST", "/api/workouts", trainer, "{\"name\":\"Pierna\"}", 201);
        // Solo entrenador: no lleva nutrición de su cliente.
        AppUser c2 = save("nr-c2-" + UUID.randomUUID().toString().substring(0, 8) + "@test", Role.CLIENT, trainingOnlyId, false, true);
        send("POST", "/api/nutrition/plans", trainingOnly, "{\"clientId\":" + c2.getId() + ",\"name\":\"P\",\"dailyKcal\":2000}", 403);
        // Al menos un servicio.
        send("PUT", "/api/auth/me/services", trainer, "{\"training\":false,\"nutrition\":false}", 400);
        JsonNode me = send("PUT", "/api/auth/me/services", trainer, "{\"training\":false,\"nutrition\":true}", 200);
        assertFalse(me.get("trainingEnabled").asBoolean());
        send("POST", "/api/workouts", trainer, "{\"name\":\"Brazo\"}", 403);
        send("PUT", "/api/auth/me/services", client, "{\"training\":true,\"nutrition\":true}", 403);
        // Su cliente ya no tiene responsable de entrenamiento.
        assertFalse(send("GET", "/api/auth/me", client, null, 200).get("trainingEnabled").asBoolean());
    }
}
