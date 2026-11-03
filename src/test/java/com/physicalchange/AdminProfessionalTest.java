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
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * El ADMIN también es profesional (T225, T226, defectos del usuario 2026-11-03): un cliente que da de alta el ADMIN tiene
 * responsable de entrenamiento, y el ADMIN puede activar su servicio de nutrición y ser el responsable de nutrición.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminProfessionalTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private AppUser admin;
    private String adminAuth;

    @BeforeEach void data() {
        admin = users.findByEmailIgnoreCase("admin").orElseThrow();
        adminAuth = token(admin.getId(), Role.ADMIN);
    }

    @AfterEach void restore() {
        AppUser a = users.findById(admin.getId()).orElseThrow();
        a.setNutritionEnabled(false); a.setTrainingEnabled(true);
        users.save(a);
    }

    @Test void clienteDelAdminTieneResponsableDeEntrenamiento() throws Exception {
        long clientId = newClient();
        JsonNode me = send("GET", "/api/auth/me", token(clientId, Role.CLIENT), null, 200);
        assertTrue(me.get("trainingEnabled").asBoolean(), "el cliente del ADMIN debe ver su Entreno");
        assertEquals(admin.getFullName(), me.get("trainingResponsibleName").asText());
    }

    @Test void adminActivaNutricionYEsElResponsable() throws Exception {
        long clientId = newClient();
        String client = token(clientId, Role.CLIENT);
        assertFalse(send("GET", "/api/auth/me", client, null, 200).get("nutritionEnabled").asBoolean());

        send("PUT", "/api/nutrition/service", adminAuth, "{\"enabled\":true}", 200);   // antes: 403 Access Denied
        JsonNode me = send("GET", "/api/auth/me", client, null, 200);
        assertTrue(me.get("nutritionEnabled").asBoolean());
        assertEquals(admin.getFullName(), me.get("nutritionResponsibleName").asText());

        // Configuración → Servicios también vale para el ADMIN; y aparece en la lista de nutricionistas asignables.
        send("PUT", "/api/auth/me/services", adminAuth, "{\"training\":true,\"nutrition\":true}", 200);
        boolean listed = false;
        for (JsonNode p : send("GET", "/api/nutrition/professionals", adminAuth, null, 200)) listed |= p.get("id").asLong() == admin.getId();
        assertTrue(listed);
        send("PUT", "/api/clients/" + clientId + "/nutritionist", adminAuth, "{\"nutritionistId\":" + admin.getId() + "}", 200);
    }

    @Test void clienteDelAdminLeTieneComoEntrenadorYLePuedeEscribir() throws Exception {
        long clientId = newClient();
        assertEquals(admin.getId(), users.findById(clientId).orElseThrow().getTrainerId());   // T227: antes NULL
        String client = token(clientId, Role.CLIENT);
        assertEquals(admin.getId(), send("GET", "/api/auth/me", client, null, 200).get("trainerId").asLong());
        send("POST", "/api/messages", client, "{\"recipientId\":" + admin.getId() + ",\"body\":\"Hola\"}", 201);
    }

    @Test void unClienteNoPuedeActivarServicios() throws Exception {
        String client = token(newClient(), Role.CLIENT);
        send("PUT", "/api/nutrition/service", client, "{\"enabled\":true}", 403);
        send("PUT", "/api/auth/me/services", client, "{\"training\":true,\"nutrition\":true}", 403);
    }

    private long newClient() throws Exception {
        String email = "c-" + UUID.randomUUID().toString().substring(0, 8) + "@test";
        return send("POST", "/api/clients", adminAuth,
                "{\"email\":\"" + email + "\",\"password\":\"Password1!\",\"fullName\":\"Cliente " + email + "\"}", 201).get("id").asLong();
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
