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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Productos, planes, cupones, suscripciones y bonos (T68, docs/api-negocio.md, ADR-004 §3–§5). */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ClientProfileRepository profiles;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private final LocalDate today = LocalDate.now();
    private final LocalDate monday = today.plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private String sfx;
    private Long trainerId, clientId, otherTrainerId;
    private String trainer, client, otherTrainer;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
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
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private long typeId(String auth, String name) throws Exception {
        for (JsonNode t : send("GET", "/api/agenda/session-types", auth, null, 200)) if (t.get("name").asText().equals(name)) return t.get("id").asLong();
        throw new AssertionError("Sin tipo " + name);
    }

    private String product(String name, String price, String bonuses, String tags, String extra) {
        return "{\"name\":\"" + name + "\",\"description\":\"Descripción\",\"price\":" + price + ",\"taxRate\":21,\"cover\":\"brand\",\"bonuses\":["
                + bonuses + "],\"autoTagIds\":[" + tags + "]" + extra + "}";
    }

    private String bonus(long type, int sessions, int days) {
        return "{\"sessionTypeId\":" + type + ",\"sessions\":" + sessions + ",\"validDays\":" + days + "}";
    }

    private String receipt(String lines, String status) {
        return "{\"clientId\":" + clientId + ",\"issueDate\":\"" + today + "\",\"method\":\"CASH\",\"status\":\"" + status + "\",\"lines\":[" + lines + "]}";
    }

    @Test void productosConBonosEtiquetasYArchivado() throws Exception {
        long type = typeId(trainer, "Yoga");
        long tag = send("POST", "/api/tags", trainer, "{\"name\":\"vip-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        JsonNode p = send("POST", "/api/products", trainer, product("Bono yoga", "49.9", bonus(type, 5, 90), String.valueOf(tag), ""), 201);
        long id = p.get("id").asLong();
        assertEquals("HIDDEN", p.get("visibility").asText());
        assertTrue(p.get("hasBonus").asBoolean());
        assertTrue(p.get("automated").asBoolean());
        assertEquals("Yoga", p.get("bonuses").get(0).get("sessionTypeName").asText());
        assertEquals(0, p.get("sales").asLong());

        send("POST", "/api/products", trainer, product("X", "10", "", "", ",\"visibility\":\"ALL\""), 409);
        send("POST", "/api/products", trainer, product("X", "10", bonus(typeId(otherTrainer, "Yoga"), 1, 30), "", ""), 400);
        send("POST", "/api/products", trainer, product("X", "10", "", "", "").replace("\"brand\"", "\"nope\""), 400);
        String six = String.join(",", java.util.Collections.nCopies(6, bonus(type, 1, 30)));
        send("POST", "/api/products", trainer, product("X", "10", six, "", ""), 400);
        send("GET", "/api/products/" + id, otherTrainer, null, 404);
        send("PUT", "/api/products/" + id, otherTrainer, product("Mío", "1", "", "", ""), 404);

        JsonNode upd = send("PUT", "/api/products/" + id, trainer, product("Bono yoga 10", "89", bonus(type, 10, 120), "", ""), 200);
        assertEquals(10, upd.get("bonuses").get(0).get("sessions").asInt());
        assertFalse(upd.get("automated").asBoolean());

        // Con ventas no se borra: se archiva. Sin ventas, 204.
        send("POST", "/api/receipts", trainer, receipt("{\"kind\":\"PRODUCT\",\"refId\":" + id + ",\"quantity\":2}", "PENDING"), 201);
        assertEquals(2, send("GET", "/api/products/" + id, trainer, null, 200).get("sales").asLong());
        assertTrue(send("DELETE", "/api/products/" + id, trainer, null, 200).get("archived").asBoolean());
        assertEquals(0, send("GET", "/api/products", trainer, null, 200).get("totalElements").asInt());
        assertEquals(1, send("GET", "/api/products?archived=true", trainer, null, 200).get("totalElements").asInt());
        long free = send("POST", "/api/products", trainer, product("Suelto", "5", "", "", ""), 201).get("id").asLong();
        send("DELETE", "/api/products/" + free, trainer, null, 204);
    }

    @Test void cupones() throws Exception {
        JsonNode c = send("POST", "/api/coupons", trainer,
                "{\"code\":\"verano20\",\"type\":\"PERCENT\",\"value\":20,\"appliesTo\":\"ALL\",\"maxUses\":1,\"active\":true}", 201);
        long id = c.get("id").asLong();
        assertEquals("VERANO20", c.get("code").asText());
        assertEquals("ACTIVE", c.get("status").asText());
        send("POST", "/api/coupons", trainer, "{\"code\":\"VERANO20\",\"type\":\"AMOUNT\",\"value\":5,\"appliesTo\":\"ALL\",\"active\":true}", 409);
        send("POST", "/api/coupons", otherTrainer, "{\"code\":\"VERANO20\",\"type\":\"AMOUNT\",\"value\":5,\"appliesTo\":\"ALL\",\"active\":true}", 201);
        send("POST", "/api/coupons", trainer, "{\"code\":\"con espacio\",\"type\":\"AMOUNT\",\"value\":5,\"appliesTo\":\"ALL\",\"active\":true}", 400);
        send("POST", "/api/coupons", trainer, "{\"code\":\"MUCHO\",\"type\":\"PERCENT\",\"value\":150,\"appliesTo\":\"ALL\",\"active\":true}", 400);

        send("POST", "/api/receipts", trainer, receipt("{\"kind\":\"FREE\",\"name\":\"Sesión\",\"unitPrice\":50,\"quantity\":1,\"taxRate\":21}", "PAID")
                .replace("\"lines\"", "\"couponId\":" + id + ",\"lines\""), 201);
        JsonNode list = send("GET", "/api/coupons?q=ver", trainer, null, 200);
        assertEquals("EXHAUSTED", list.get("content").get(0).get("status").asText());
        assertEquals(1, list.get("content").get(0).get("uses").asInt());
        send("DELETE", "/api/coupons/" + id, trainer, null, 409);
        send("PUT", "/api/coupons/" + id, trainer, "{\"code\":\"VERANO20\",\"type\":\"PERCENT\",\"value\":20,\"appliesTo\":\"ALL\",\"maxUses\":1,\"active\":false}", 200);
        send("DELETE", "/api/coupons/" + id, otherTrainer, null, 404);
    }

    @Test void suscripcionesEmitenUnComprobantePorPeriodo() throws Exception {
        long plan = send("POST", "/api/subscription-plans", trainer,
                product("Mensual", "60", "", "", ",\"frequency\":\"MONTHLY\""), 201).get("id").asLong();
        LocalDate start = today.minusMonths(2);
        JsonNode s = send("POST", "/api/subscriptions", trainer,
                "{\"clientId\":" + clientId + ",\"planId\":" + plan + ",\"startDate\":\"" + start + "\"}", 201);
        assertEquals(start.plusMonths(3).toString(), s.get("nextPeriodStart").asText());
        send("POST", "/api/subscriptions", trainer, "{\"clientId\":" + clientId + ",\"planId\":" + plan + ",\"startDate\":\"" + today + "\"}", 409);
        send("POST", "/api/subscriptions", otherTrainer, "{\"clientId\":" + clientId + ",\"planId\":" + plan + ",\"startDate\":\"" + today + "\"}", 403);

        String url = "/api/receipts?statuses=PENDING&from=" + start + "&to=" + today;
        JsonNode r = send("GET", url, trainer, null, 200);
        assertEquals(3, r.get("totalElements").asInt());
        for (JsonNode x : r.get("content")) {
            assertEquals("SUBSCRIPTION", x.get("origin").asText());
            assertEquals("PLAN", x.get("lines").get(0).get("kind").asText());
        }
        assertEquals(3, send("GET", url, trainer, null, 200).get("totalElements").asInt());   // idempotente
        assertEquals(1, send("GET", "/api/subscription-plans/" + plan, trainer, null, 200).get("activeSubscriptions").asLong());
        // Con suscripciones, el plan se archiva en lugar de borrarse.
        long subId = s.get("id").asLong();
        assertEquals("CANCELLED", send("POST", "/api/subscriptions/" + subId + "/cancel", trainer, null, 200).get("status").asText());
        send("POST", "/api/subscriptions/" + subId + "/cancel", trainer, null, 409);
        assertTrue(send("DELETE", "/api/subscription-plans/" + plan, trainer, null, 200).get("archived").asBoolean());
    }

    @Test void bonosSeConcedenSeGastanYSeDevuelven() throws Exception {
        long type = typeId(trainer, "Yoga");
        long tag = send("POST", "/api/tags", trainer, "{\"name\":\"bono-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        long product = send("POST", "/api/products", trainer, product("Bono 2", "30", bonus(type, 2, 60), String.valueOf(tag), ""), 201).get("id").asLong();
        long receiptId = send("POST", "/api/receipts", trainer, receipt("{\"kind\":\"PRODUCT\",\"refId\":" + product + ",\"quantity\":1}", "PENDING"), 201)
                .get("id").asLong();
        assertEquals(0, send("GET", "/api/me/bonuses", client, null, 200).size());   // aún pendiente
        send("POST", "/api/receipts/" + receiptId + "/pay", trainer, null, 200);

        JsonNode mine = send("GET", "/api/me/bonuses", client, null, 200);
        assertEquals(1, mine.size());
        assertEquals(2, mine.get(0).get("remaining").asInt());
        Boolean tagged = tx.execute(st -> profiles.findById(clientId).orElseThrow().getTagIds().contains(tag));
        assertEquals(Boolean.TRUE, tagged, "Etiqueta automática");

        String session = "{\"kind\":\"SESSION\",\"title\":\"Yoga\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + type + ",\"cover\":\"gym\",\"date\":\""
                + monday + "\",\"startTime\":\"18:30\",\"endTime\":\"19:30\",\"bookable\":\"YES\"}";
        long event = send("POST", "/api/agenda/events", trainer, session, 201).get(0).get("id").asLong();
        long booking = send("POST", "/api/agenda/events/" + event + "/bookings", client, "{\"clientId\":" + clientId + "}", 201).get("id").asLong();
        assertEquals(1, send("GET", "/api/me/bonuses", client, null, 200).get(0).get("remaining").asInt());
        assertEquals(1, send("GET", "/api/agenda/stats?from=" + monday + "&to=" + monday, trainer, null, 200).get("vouchersRedeemed").asLong());

        send("PATCH", "/api/agenda/bookings/" + booking, client, "{\"status\":\"CANCELLED\"}", 200);
        assertEquals(2, send("GET", "/api/me/bonuses", client, null, 200).get(0).get("remaining").asInt());
        assertEquals(0, send("GET", "/api/agenda/stats?from=" + monday + "&to=" + monday, trainer, null, 200).get("vouchersRedeemed").asLong());

        // Anular el comprobante pagado cancela lo no usado.
        send("POST", "/api/receipts/" + receiptId + "/void", trainer, "{\"reason\":\"Devolución\"}", 200);
        assertEquals(0, send("GET", "/api/me/bonuses", client, null, 200).size());
        JsonNode all = send("GET", "/api/clients/" + clientId + "/bonuses", trainer, null, 200);
        assertEquals("CANCELLED", all.get(0).get("status").asText());
        send("GET", "/api/clients/" + clientId + "/bonuses", otherTrainer, null, 403);
    }

    @Test void enlaceDeInvitacionConPlanDePago() throws Exception {
        long plan = send("POST", "/api/subscription-plans", trainer,
                product("Semanal", "15", "", "", ",\"frequency\":\"WEEKLY\""), 201).get("id").asLong();
        long otherPlan = send("POST", "/api/subscription-plans", otherTrainer,
                product("Ajeno", "15", "", "", ",\"frequency\":\"WEEKLY\""), 201).get("id").asLong();
        send("POST", "/api/invitations/link", trainer, "{\"planId\":" + otherPlan + "}", 400);
        JsonNode link = send("POST", "/api/invitations/link", trainer, "{\"planId\":" + plan + "}", 201);
        assertEquals(plan, link.get("planId").asLong());
        JsonNode login = send("POST", "/api/public/invitations/" + link.get("token").asText() + "/accept", null,
                "{\"fullName\":\"Con plan\",\"email\":\"plan-" + sfx + "@test\",\"password\":\"Password1!\"}", 201);
        long newClient = login.get("user").get("id").asLong();
        JsonNode subs = send("GET", "/api/subscriptions?clientId=" + newClient, trainer, null, 200);
        assertEquals(1, subs.size());
        assertEquals(today.toString(), subs.get(0).get("startDate").asText());
        JsonNode r = send("GET", "/api/receipts?clientIds=" + newClient, trainer, null, 200);
        assertEquals(1, r.get("totalElements").asInt());
        assertEquals(0, new BigDecimal("15").compareTo(r.get("content").get(0).get("total").decimalValue()));
    }
}
