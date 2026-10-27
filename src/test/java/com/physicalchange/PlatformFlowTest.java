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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Servicios de la plataforma y sus pagos (T99, /api/platform, solo ADMIN). */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String admin, trainer, sfx;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
        admin = token(users.findByEmailIgnoreCase("admin").orElseThrow().getId(), Role.ADMIN);
        AppUser t = new AppUser(); t.setEmail("t-" + sfx + "@test"); t.setFullName("T"); t.setRole(Role.TRAINER); t.setPasswordHash(enc.encode("Password1!"));
        trainer = token(users.save(t).getId(), Role.TRAINER);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private String service(String name, String cost, String url) {
        return "{\"name\":\"" + name + "\",\"purpose\":\"Backend\",\"plan\":\"Starter\",\"monthlyCost\":" + cost
                + (url == null ? "" : ",\"dashboardUrl\":\"" + url + "\"") + ",\"active\":true}";
    }

    /** QA T80: el admin es el profesional en producción y su Inicio pedía este panel con 403. */
    @Test void elAdminTambienTienePanelDeProfesional() throws Exception {
        JsonNode d = send("GET", "/api/dashboard/trainer", admin, null, 200);
        assertTrue(d.get("clients").asLong() >= 0);
        send("GET", "/api/dashboard/trainer", trainer, null, 200);
    }

    @Test void serviciosYPagos() throws Exception {
        send("GET", "/api/platform/services", trainer, null, 403);
        send("POST", "/api/platform/services", trainer, service("X-" + sfx, "1", null), 403);

        BigDecimal before = send("GET", "/api/platform/services", admin, null, 200).get("monthlyTotal").decimalValue();
        long id = send("POST", "/api/platform/services", admin, service("Render-" + sfx, "7", "https://dashboard.render.com"), 201).get("id").asLong();
        send("POST", "/api/platform/services", admin, service("render-" + sfx, "1", null), 409);
        send("POST", "/api/platform/services", admin, service("Y-" + sfx, "1", "http://inseguro"), 400);
        JsonNode list = send("GET", "/api/platform/services", admin, null, 200);
        assertEquals(0, before.add(new BigDecimal("7")).compareTo(list.get("monthlyTotal").decimalValue()));
        assertEquals(0, list.get("monthlyTotal").decimalValue().multiply(BigDecimal.valueOf(12)).compareTo(list.get("yearlyEstimate").decimalValue()));

        String pay = "{\"serviceId\":" + id + ",\"date\":\"" + LocalDate.now() + "\",\"concept\":\"Plan Starter\",\"amount\":7,\"status\":\"PAID\"}";
        long payment = send("POST", "/api/platform/payments", admin, pay, 201).get("id").asLong();
        send("POST", "/api/platform/payments", admin, pay.replace("\"PAID\"", "\"PAID\",\"fileId\":999999"), 404);
        send("POST", "/api/platform/payments", trainer, pay, 403);
        JsonNode payments = send("GET", "/api/platform/payments?serviceId=" + id, admin, null, 200);
        assertEquals(1, payments.get("totalElements").asInt());
        assertEquals("Render-" + sfx, payments.get("content").get(0).get("serviceName").asText());
        JsonNode s = send("GET", "/api/platform/services", admin, null, 200);
        for (JsonNode x : s.get("services")) if (x.get("id").asLong() == id) assertEquals(1, x.get("paymentsCount").asLong());

        send("DELETE", "/api/platform/services/" + id, admin, null, 409);
        send("PUT", "/api/platform/payments/" + payment, admin, pay.replace("\"PAID\"", "\"PENDING\""), 200);
        send("DELETE", "/api/platform/payments/" + payment, admin, null, 204);
        send("DELETE", "/api/platform/services/" + id, admin, null, 204);
        send("DELETE", "/api/platform/services/" + id, admin, null, 404);
    }
}
