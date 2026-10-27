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

/** Datos de negocio: finanzas, clientes, servicios e informes (T69, docs/api-negocio.md). */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ClientProfileRepository profiles;
    @Autowired WorkoutAssignmentRepository assignments;
    @Autowired ClientLeadRepository leads;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private final LocalDate today = LocalDate.now();
    private String sfx;
    private Long trainerId, clientId, client2Id;
    private String trainer, otherTrainer;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        client2Id = save("c2-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        otherTrainer = token(save("ot-" + sfx + "@test", Role.TRAINER, null).getId(), Role.TRAINER);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private MvcResult raw(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        return mvc.perform(req).andExpect(status().is(status)).andReturn();
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        String text = raw(method, url, auth, body, status).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private long free(String name, String price, String status) throws Exception {
        return send("POST", "/api/receipts", trainer, "{\"clientId\":" + client2Id + ",\"issueDate\":\"" + today + "\",\"method\":\"CASH\",\"status\":\""
                + status + "\",\"lines\":[{\"kind\":\"FREE\",\"name\":\"" + name + "\",\"unitPrice\":" + price + ",\"quantity\":1,\"taxRate\":21}]}", 201)
                .get("id").asLong();
    }

    private static void eq(String expected, JsonNode n) { assertEquals(0, new BigDecimal(expected).compareTo(n.decimalValue()), "esperado " + expected + " y es " + n); }

    @Test void finanzas() throws Exception {
        long plan = send("POST", "/api/subscription-plans", trainer, "{\"name\":\"Mensual\",\"description\":\"d\",\"price\":60,\"taxRate\":21,"
                + "\"cover\":\"brand\",\"frequency\":\"MONTHLY\"}", 201).get("id").asLong();
        send("POST", "/api/subscriptions", trainer, "{\"clientId\":" + clientId + ",\"planId\":" + plan + ",\"startDate\":\"" + today.minusMonths(1) + "\"}", 201);
        String range = "from=" + today.minusDays(40) + "&to=" + today;
        for (JsonNode r : send("GET", "/api/receipts?statuses=PENDING&" + range, trainer, null, 200).get("content")) {
            send("POST", "/api/receipts/" + r.get("id").asLong() + "/pay", trainer, null, 200);
        }
        free("ClaseSuelta", "30", "PAID");
        free("Pendiente", "10", "PENDING");
        long toVoid = free("Devuelta", "20", "PAID");
        send("POST", "/api/receipts/" + toVoid + "/void", trainer, "{\"reason\":\"Devolución\"}", 200);

        JsonNode f = send("GET", "/api/business/finance?" + range, trainer, null, 200);
        JsonNode k = f.get("kpis");
        eq("150", k.get("totalSold"));
        eq("120", k.get("subscriptionSales"));
        eq("30", k.get("productSales"));
        eq("180", k.get("receiptsCreated"));
        eq("20", k.get("voidedAmount"));
        assertTrue(f.get("platformCost").isNull());
        eq("10", f.get("receiptStatus").get("pendingAmount"));
        JsonNode day = f.get("subscriptionsChart").get("days").get(40);
        assertEquals(today.toString(), day.get("date").asText());
        eq("60", day.get("newSales"));
        eq("60", day.get("renewals"));
        eq("150", f.get("receiptsChart").get("days").get(40).get("amount"));

        JsonNode items = send("GET", "/api/business/finance/by-item?kinds=PLAN&" + range, trainer, null, 200);
        assertEquals(1, items.get("totalElements").asInt());
        assertEquals(2, items.get("content").get(0).get("units").asLong());
        eq("120", items.get("content").get(0).get("income"));
        // Anulados y pendientes no cuentan: el plan y la clase suelta.
        assertEquals(2, send("GET", "/api/business/finance/by-item?" + range, trainer, null, 200).get("totalElements").asInt());
        JsonNode sales = send("GET", "/api/business/finance/by-item/PLAN/" + plan + "?" + range, trainer, null, 200);
        assertEquals(2, sales.get("sales").asLong());
        assertEquals(2, sales.get("buyers").size());
        assertEquals(1, send("GET", "/api/business/finance/by-item/FREE/0?name=ClaseSuelta&" + range, trainer, null, 200).get("sales").asLong());
        assertTrue(send("GET", "/api/business/finance/day?date=" + today, trainer, null, 200).size() >= 4);

        eq("0", send("GET", "/api/business/finance?" + range, otherTrainer, null, 200).get("kpis").get("totalSold"));
        send("GET", "/api/business/finance?from=" + today.minusDays(400) + "&to=" + today, trainer, null, 400);
    }

    @Test void clientesYEstados() throws Exception {
        profiles.save(new ClientProfile(clientId));
        profiles.save(new ClientProfile(client2Id));
        AppUser archived = save("a-" + sfx + "@test", Role.CLIENT, trainerId);
        archived.setActive(false);
        users.save(archived);
        // client: workout vencido sin hacer (cumplimiento 0 %); client2: sin actividad ni planificación.
        WorkoutAssignment w = new WorkoutAssignment();
        w.setWorkoutId(1L); w.setClientId(clientId); w.setScheduledDate(today.minusDays(2));
        assignments.save(w);
        ClientLead lead = new ClientLead();
        lead.setTrainerId(trainerId); lead.setName("Lead " + sfx); lead.setEmail("lead-" + sfx + "@test"); lead.setSource("WEB");
        leads.save(lead);
        send("POST", "/api/invitations", trainer, "{\"email\":\"inv-" + sfx + "@test\",\"name\":\"Invitada\"}", 201);

        JsonNode c = send("GET", "/api/business/clients?from=" + today.minusDays(6) + "&to=" + today, trainer, null, 200);
        assertEquals(2, c.get("kpis").get("totalClients").asLong());
        assertEquals(2, c.get("kpis").get("newClients").asLong());
        assertEquals(7, c.get("evolution").get("days").size());
        assertEquals(2, c.get("evolution").get("days").get(6).get("value").asLong());
        JsonNode s = c.get("states");
        assertEquals(1, s.get("contacted").get("leads").asLong());
        assertEquals(1, s.get("contacted").get("notRegistered").asLong());
        assertEquals(2, s.get("waiting").get("notStarted").asLong());
        assertEquals(2, s.get("waiting").get("withoutPlanning").asLong());
        assertEquals(1, s.get("atRisk").get("lowCompliance").asLong());
        assertEquals(1, s.get("finished").get("archived").asLong());
        assertEquals(2, s.get("active").get("withoutSubscription").asLong());

        assertFalse(send("GET", "/api/business/clients?series=ARCHIVED", trainer, null, 200).get("evolution").get("available").asBoolean());
        JsonNode risk = send("GET", "/api/business/clients/states/lowCompliance", trainer, null, 200);
        assertEquals(clientId, risk.get(0).get("clientId").asLong());
        assertTrue(risk.get(0).get("detail").asText().contains("0 %"));
        assertTrue(send("GET", "/api/business/clients/states/leads", trainer, null, 200).get(0).get("clientId").isNull());
        send("GET", "/api/business/clients/states/nope", trainer, null, 404);
        assertEquals(2, send("GET", "/api/business/clients/day?date=" + today + "&series=NEW", trainer, null, 200).get("total").asLong());
        assertEquals(0, send("GET", "/api/business/clients", otherTrainer, null, 200).get("kpis").get("totalClients").asLong());
    }

    @Test void serviciosSinDatosNoSonCero() throws Exception {
        JsonNode empty = send("GET", "/api/business/services", trainer, null, 200);
        assertTrue(empty.get("kpis").get("avgCompliance").isNull());
        for (JsonNode t : empty.get("complianceByType")) {
            assertFalse(t.get("available").asBoolean());
            assertTrue(t.get("pct").isNull());
        }
        assertFalse(empty.get("ratings").get("available").asBoolean());

        WorkoutAssignment done = new WorkoutAssignment();
        done.setWorkoutId(1L); done.setClientId(clientId); done.setScheduledDate(today.minusDays(1)); done.setCompletedDate(today.minusDays(1));
        assignments.save(done);
        WorkoutAssignment missed = new WorkoutAssignment();
        missed.setWorkoutId(1L); missed.setClientId(clientId); missed.setScheduledDate(today.minusDays(1));
        assignments.save(missed);
        JsonNode s = send("GET", "/api/business/services", trainer, null, 200);
        JsonNode workouts = s.get("complianceByType").get(1);
        assertEquals("WORKOUTS", workouts.get("type").asText());
        assertEquals(50, workouts.get("pct").asInt());
        assertEquals(50, s.get("kpis").get("avgCompliance").asInt());
        assertEquals(2, s.get("kpis").get("assignedActivities").asLong());
    }

    @Test void informes() throws Exception {
        long id = free("=1+1", "25", "PAID");
        send("POST", "/api/invitations", trainer, "{\"email\":\"inv-" + sfx + "@test\",\"name\":\"Invitada\"}", 201);
        String range = "from=" + today + "&to=" + today;

        String billing = raw("GET", "/api/business/reports/billing?format=csv&" + range, trainer, null, 200).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(billing.contains("PC-" + id));
        assertTrue(billing.contains("'=1+1"));
        MvcResult x = raw("GET", "/api/business/reports/billing?format=xlsx&" + range, trainer, null, 200);
        assertTrue(x.getResponse().getHeader("Content-Disposition").contains("physical-change-billing-" + today + "-" + today + ".xlsx"));

        String signups = raw("GET", "/api/business/reports/incomplete-signups?format=csv&" + range, trainer, null, 200).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(signups.contains("inv-" + sfx + "@test"));
        String team = raw("GET", "/api/business/reports/team-performance?format=csv&" + range, trainer, null, 200).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(2, team.strip().split("\r\n").length);
        raw("GET", "/api/business/reports/client-compliance?format=csv&" + range, trainer, null, 200);

        send("GET", "/api/business/reports/billing?format=csv", trainer, null, 400);
        send("GET", "/api/business/reports/billing?format=pdf&" + range, trainer, null, 400);
        send("GET", "/api/business/reports/nope?format=csv&" + range, trainer, null, 404);
        send("GET", "/api/business/finance", token(clientId, Role.CLIENT), null, 403);
    }
}
