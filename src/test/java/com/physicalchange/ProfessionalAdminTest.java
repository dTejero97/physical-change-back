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
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Alta de profesionales solo por el ADMIN (T228, docs/api-profesionales.md). */
@SpringBootTest
@AutoConfigureMockMvc
class ProfessionalAdminTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String adminAuth;

    @BeforeEach void data() { adminAuth = token(users.findByEmailIgnoreCase("admin").orElseThrow().getId(), Role.ADMIN); }

    @Test void elAdminCreaUnProfesionalQueEntraConSuUsuario() throws Exception {
        String user = "pro" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode p = send("POST", "/api/admin/professionals", adminAuth, body(user, "Contrasena123.", true, true), 201);
        assertEquals(user, p.get("username").asText());
        assertTrue(p.get("trainingEnabled").asBoolean() && p.get("nutritionEnabled").asBoolean());
        assertFalse(p.toString().contains("Contrasena") || p.toString().contains("$2a$"));
        AppUser saved = users.findById(p.get("id").asLong()).orElseThrow();
        assertEquals(Role.TRAINER, saved.getRole());

        // Inicia sesión con el nombre corto (en mayúsculas también) y es profesional de los dos servicios.
        JsonNode login = send("POST", "/api/auth/login", null, "{\"username\":\"" + user.toUpperCase() + "\",\"password\":\"Contrasena123.\"}", 200);
        assertEquals("TRAINER", login.get("user").get("role").asText());
        assertTrue(login.get("user").get("nutritionEnabled").asBoolean());

        send("POST", "/api/admin/professionals", adminAuth, body(user, "Otra12345", true, false), 409);   // repetido
        boolean listed = false;
        for (JsonNode x : send("GET", "/api/admin/professionals", adminAuth, null, 200)) listed |= x.get("id").asLong() == p.get("id").asLong();
        assertTrue(listed);
    }

    @Test void validaUsuarioServiciosYContrasena() throws Exception {
        send("POST", "/api/admin/professionals", adminAuth, body("admin", "Contrasena123.", true, false), 400);       // reservado
        send("POST", "/api/admin/professionals", adminAuth, body("Con Espacio", "Contrasena123.", true, false), 400); // formato
        send("POST", "/api/admin/professionals", adminAuth, body("nuevo" + UUID.randomUUID().toString().substring(0, 4), "corta", true, false), 400);
        send("POST", "/api/admin/professionals", adminAuth, body("nuevo" + UUID.randomUUID().toString().substring(0, 4), "Contrasena123.", false, false), 400);
        send("POST", "/api/admin/professionals", adminAuth, body("ana" + UUID.randomUUID().toString().substring(0, 4) + "@ejemplo.com", "Contrasena123.", false, true), 201);
    }

    @Test void soloElAdmin() throws Exception {
        AppUser t = new AppUser(); t.setEmail("t-" + UUID.randomUUID() + "@test"); t.setFullName("T"); t.setRole(Role.TRAINER);
        t.setPasswordHash(enc.encode("Password1!")); t = users.save(t);
        String trainer = token(t.getId(), Role.TRAINER);
        send("GET", "/api/admin/professionals", trainer, null, 403);
        send("POST", "/api/admin/professionals", trainer, body("x" + UUID.randomUUID().toString().substring(0, 6), "Contrasena123.", true, true), 403);
        send("POST", "/api/admin/professionals", null, body("y" + UUID.randomUUID().toString().substring(0, 6), "Contrasena123.", true, true), 401);
    }

    @Test void desactivarYCambiarContrasenaCierranSesiones() throws Exception {
        String user = "pro" + UUID.randomUUID().toString().substring(0, 6);
        long id = send("POST", "/api/admin/professionals", adminAuth, body(user, "Contrasena123.", true, false), 201).get("id").asLong();
        int v0 = users.findById(id).orElseThrow().getTokenVersion();
        send("PUT", "/api/admin/professionals/" + id + "/password", adminAuth, "{\"password\":\"NuevaClave123\"}", 204);
        assertTrue(users.findById(id).orElseThrow().getTokenVersion() > v0);
        send("POST", "/api/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"NuevaClave123\"}", 200);
        JsonNode off = send("PUT", "/api/admin/professionals/" + id, adminAuth, "{\"fullName\":\"Nombre\",\"training\":true,\"nutrition\":true,\"active\":false}", 200);
        assertFalse(off.get("active").asBoolean());
        send("POST", "/api/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"NuevaClave123\"}", 401);
        // Un id que no es un profesional (el propio ADMIN) → 404.
        long adminId = users.findByEmailIgnoreCase("admin").orElseThrow().getId();
        send("PUT", "/api/admin/professionals/" + adminId + "/password", adminAuth, "{\"password\":\"NuevaClave123\"}", 404);
    }

    private static String body(String user, String password, boolean training, boolean nutrition) {
        return "{\"username\":\"" + user + "\",\"fullName\":\"Profesional " + user + "\",\"password\":\"" + password
                + "\",\"training\":" + training + ",\"nutrition\":" + nutrition + "}";
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
