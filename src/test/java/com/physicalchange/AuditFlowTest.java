package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.audit.AuditQueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Registro de auditoría (T233, ADR-016, docs/api-auditoria.md). */
@SpringBootTest
@AutoConfigureMockMvc
class AuditFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository events;
    @Autowired AuditQueryService query;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String admin;

    @BeforeEach void data() { admin = token(users.findByEmailIgnoreCase("admin").orElseThrow().getId(), Role.ADMIN); }

    @Test void registraAccionesYSoloLoVeElAdmin() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        AppUser t = save("t-" + sfx + "@test", Role.TRAINER, null);
        AppUser c = save("c-" + sfx + "@test", Role.CLIENT, t.getId());
        String trainer = token(t.getId(), Role.TRAINER);

        // Login fallido: queda el usuario enmascarado, nunca la contraseña.
        send("POST", "/api/auth/login", null, "{\"username\":\"nadie-" + sfx + "@ejemplo.com\",\"password\":\"Secreta123!\"}", 401);
        // Comprobante creado y pagado por el profesional.
        String body = "{\"clientId\":" + c.getId() + ",\"issueDate\":\"" + LocalDate.now() + "\",\"method\":\"BIZUM\",\"status\":\"PENDING\","
                + "\"lines\":[{\"kind\":\"FREE\",\"name\":\"Sesión\",\"unitPrice\":30,\"quantity\":1,\"taxRate\":21}]}";
        long receipt = send("POST", "/api/receipts", trainer, body, 201).get("id").asLong();
        send("POST", "/api/receipts/" + receipt + "/pay", trainer, "{\"method\":\"BIZUM\"}", 200);

        send("GET", "/api/admin/audit", trainer, null, 403);
        JsonNode page = send("GET", "/api/admin/audit?clientId=" + c.getId(), admin, null, 200);
        assertEquals(2, page.get("totalElements").asInt());
        JsonNode paid = page.get("content").get(0);                       // más reciente primero
        assertEquals("RECEIPT_PAID", paid.get("action").asText());
        assertEquals(t.getId(), paid.get("actorId").asLong());
        assertEquals("Comprobante pagado", paid.get("actionLabel").asText());
        assertTrue(paid.get("summary").asText().contains("PC-" + receipt));

        JsonNode failed = send("GET", "/api/admin/audit?action=LOGIN_FAILED&q=ejemplo.com&size=200", admin, null, 200).get("content");
        boolean masked = false;
        for (JsonNode e : failed) {
            String s = e.get("summary").asText();
            assertFalse(s.contains("Secreta123!"));
            if (s.equals("Usuario: n***@ejemplo.com")) masked = true;
        }
        assertTrue(masked, "el usuario tecleado va enmascarado");

        send("GET", "/api/admin/audit?action=NO_EXISTE", admin, null, 400);
        send("GET", "/api/admin/audit?from=2025-01-01&to=2026-06-01", admin, null, 400);   // más de 366 días
        assertTrue(send("GET", "/api/admin/audit/actions", admin, null, 200).size() >= 25);
    }

    @Test void exportarQuedaRegistrado() throws Exception {
        String csv = mvc.perform(get("/api/admin/audit/export").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("Fecha y hora;Persona;Rol;Acción;Cliente;Resumen;IP"));
        JsonNode exports = send("GET", "/api/admin/audit?action=EXPORT", admin, null, 200);
        assertTrue(exports.get("totalElements").asInt() >= 1);
    }

    @Test void laPurgaBorraLoDeMasDe12Meses() {
        AuditEvent old = events.save(new AuditEvent(null, null, "LOGIN_OK", "USER", null, null, "viejo", null, null));
        AuditEvent fresh = events.save(new AuditEvent(null, null, "LOGIN_OK", "USER", null, null, "reciente", null, null));
        jdbc.update("update audit_event set occurred_at = ? where id = ?", Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)), old.getId());
        query.purge();
        assertTrue(events.findById(old.getId()).isEmpty());
        assertTrue(events.findById(fresh.getId()).isPresent());
    }

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
