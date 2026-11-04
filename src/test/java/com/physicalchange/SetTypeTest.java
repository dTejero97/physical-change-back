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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tipos de serie (T220, docs/diseno-entreno-hevy.md §6): el calentamiento no cuenta en récords ni en volumen. */
@SpringBootTest
@AutoConfigureMockMvc
class SetTypeTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    @Test void elCalentamientoNoCuentaEnRecordsNiVolumen() throws Exception {
        AppUser t = save("t-" + UUID.randomUUID() + "@test", Role.TRAINER, null);
        AppUser c = save("c-" + UUID.randomUUID() + "@test", Role.CLIENT, t.getId());
        String client = "Bearer " + jwt.generate(new AuthUser(c.getId(), "x", Role.CLIENT));
        Exercise e = new Exercise(); e.setName("Sentadilla " + UUID.randomUUID().toString().substring(0, 4)); e.setMuscleGroup("Pierna");
        long ex = exercises.save(e).getId();
        String day = LocalDate.now().toString();

        JsonNode warm = send("POST", "/api/exercise-logs", client, log(ex, day, 1, 5, "200", "\"WARMUP\""), 201);
        assertEquals("WARMUP", warm.get("setType").asText());
        send("POST", "/api/exercise-logs", client, log(ex, day, 2, 10, "100", null), 201);            // sin tipo → NORMAL
        send("POST", "/api/exercise-logs", client, log(ex, day, 3, 8, "100", "\"FAILURE\""), 201);
        send("POST", "/api/exercise-logs", client, log(ex, day, 4, 1, "1", "\"OTRO\""), 400);

        JsonNode hist = send("GET", "/api/exercise-logs/client/" + c.getId(), client, null, 200);
        boolean normal = false;
        for (JsonNode h : hist) if (h.get("setNumber").asInt() == 2) normal = "NORMAL".equals(h.get("setType").asText());
        assertTrue(normal);

        JsonNode p = null;
        for (JsonNode x : send("GET", "/api/progress/strength/" + c.getId(), client, null, 200).get("exercises")) if (x.get("exerciseId").asLong() == ex) p = x;
        assertNotNull(p);
        JsonNode point = p.get("points").get(0);
        assertEquals(100, point.get("maxWeightKg").asDouble(), 0.001);        // no 200: era calentamiento
        assertEquals(1800, point.get("volumeKg").asDouble(), 0.001);          // 10×100 + 8×100; sin los 5×200 del calentamiento
    }

    private static String log(long ex, String day, int set, int reps, String kg, String type) {
        return "{\"exerciseId\":" + ex + ",\"logDate\":\"" + day + "\",\"setNumber\":" + set + ",\"reps\":" + reps + ",\"weightKg\":" + kg
                + (type == null ? "" : ",\"setType\":" + type) + "}";
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }
}
