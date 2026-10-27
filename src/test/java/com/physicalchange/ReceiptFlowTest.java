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

/** Comprobantes de pago (T67, docs/api-negocio.md, ADR-004): inmutables, permisos, cálculo, cupones, fachada /api/payments y exportación. */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiptFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CouponRepository coupons;
    @Autowired PlatformServiceEntryRepository platformServices;
    @Autowired PlatformPaymentRepository platformPayments;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private final LocalDate today = LocalDate.now();
    private Long trainerId, clientId, otherTrainerId, otherClientId;
    private String trainer, client, otherTrainer;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
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

    private String free(String name, String price, int qty) {
        return "{\"kind\":\"FREE\",\"name\":\"" + name + "\",\"unitPrice\":" + price + ",\"quantity\":" + qty + ",\"taxRate\":21}";
    }

    private String receipt(Long client, String status, String lines, String extra) {
        return "{\"clientId\":" + client + ",\"issueDate\":\"" + today + "\",\"method\":\"BIZUM\",\"status\":\"" + status + "\",\"lines\":[" + lines + "]" + extra + "}";
    }

    @Test void altaPagoYAnulacionSonInmutables() throws Exception {
        JsonNode r = send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", free("Sesión suelta", "30", 2), ""), 201);
        long id = r.get("id").asLong();
        assertEquals("PC-" + id, r.get("reference").asText());
        assertEquals(0, new BigDecimal("60.00").compareTo(r.get("total").decimalValue()));
        assertEquals("MANUAL", r.get("origin").asText());
        assertEquals(today.toString(), r.get("dueDate").asText());

        JsonNode sum = send("GET", "/api/receipts/summary?from=" + today + "&to=" + today, trainer, null, 200);
        assertEquals(1, sum.get("countPending").asLong());
        assertEquals(0, new BigDecimal("60").compareTo(sum.get("pending").decimalValue()));

        JsonNode paid = send("POST", "/api/receipts/" + id + "/pay", trainer, "{\"method\":\"CASH\"}", 200);
        assertEquals("PAID", paid.get("status").asText());
        assertEquals("CASH", paid.get("method").asText());
        assertEquals(today.toString(), paid.get("paidDate").asText());
        send("POST", "/api/receipts/" + id + "/pay", trainer, null, 409);

        JsonNode voided = send("POST", "/api/receipts/" + id + "/void", trainer, "{\"reason\":\"Devolución\"}", 200);
        assertEquals("VOID", voided.get("status").asText());
        assertEquals("Devolución", voided.get("voidReason").asText());
        send("POST", "/api/receipts/" + id + "/void", trainer, "{\"reason\":\"Otra vez\"}", 409);
        send("POST", "/api/receipts/" + id + "/void", trainer, "{\"reason\":\"\"}", 400);

        // Sin PUT ni DELETE.
        send("PUT", "/api/receipts/" + id, trainer, receipt(clientId, "PENDING", free("X", "1", 1), ""), 405);
        send("DELETE", "/api/receipts/" + id, trainer, null, 405);
    }

    @Test void soloElDuenoVeYGestionaSusComprobantes() throws Exception {
        long id = send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", free("Cuota", "50", 1), ""), 201).get("id").asLong();
        send("GET", "/api/receipts/" + id, otherTrainer, null, 404);
        send("POST", "/api/receipts/" + id + "/pay", otherTrainer, null, 404);
        send("POST", "/api/receipts/" + id + "/void", otherTrainer, "{\"reason\":\"x\"}", 404);
        assertEquals(0, send("GET", "/api/receipts", otherTrainer, null, 200).get("totalElements").asInt());
        // Cliente ajeno y rol CLIENT.
        send("POST", "/api/receipts", trainer, receipt(otherClientId, "PENDING", free("Cuota", "50", 1), ""), 403);
        send("GET", "/api/receipts", client, null, 403);
        send("POST", "/api/receipts", client, receipt(clientId, "PENDING", free("Cuota", "50", 1), ""), 403);
        // Movimientos: solo ADMIN.
        send("GET", "/api/receipts/movements", trainer, null, 403);
    }

    @Test void previsualizarCalculaSinGuardarYRedondea() throws Exception {
        String lines = free("Bono", "33.33", 3) + "," + free("Consulta", "10.005", 1);
        JsonNode p = send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PAID", lines, ",\"discountName\":\"Amigos\",\"discountPct\":15"), 200);
        assertTrue(p.get("id").isNull());
        assertEquals(0, new BigDecimal("99.99").compareTo(p.get("lines").get(0).get("lineTotal").decimalValue()));
        assertEquals(0, new BigDecimal("10.01").compareTo(p.get("lines").get(1).get("lineTotal").decimalValue()));
        assertEquals(0, new BigDecimal("110.00").compareTo(p.get("subtotal").decimalValue()));
        assertEquals(0, new BigDecimal("16.50").compareTo(p.get("discountAmount").decimalValue()));
        assertEquals(0, new BigDecimal("93.50").compareTo(p.get("total").decimalValue()));
        assertEquals(0, send("GET", "/api/receipts", trainer, null, 200).get("totalElements").asInt());
        // Validaciones.
        send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PENDING", "", ""), 400);
        send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PENDING", free("X", "0", 1), ""), 400);
        send("POST", "/api/receipts/preview", trainer,
                "{\"clientId\":" + clientId + ",\"issueDate\":\"" + today + "\",\"dueDate\":\"" + today.minusDays(1)
                        + "\",\"method\":\"CASH\",\"status\":\"PENDING\",\"lines\":[" + free("X", "5", 1) + "]}", 400);
        send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PAID", free("X", "5", 1), ",\"paidDate\":\"" + today.plusDays(1) + "\""), 400);
    }

    @Test void lineasDelCatalogoYCupones() throws Exception {
        Product p = new Product();
        p.setTrainerId(trainerId); p.setName("Bono 10 sesiones"); p.setDescription("d"); p.setPrice(new BigDecimal("250.00"));
        p.setTaxRate((short) 21); p.setCover("brand");
        p = products.save(p);
        Product archived = new Product();
        archived.setTrainerId(trainerId); archived.setName("Viejo"); archived.setDescription("d"); archived.setPrice(BigDecimal.TEN);
        archived.setTaxRate((short) 0); archived.setCover("brand"); archived.setArchived(true);
        archived = products.save(archived);

        String productLine = "{\"kind\":\"PRODUCT\",\"refId\":" + p.getId() + ",\"quantity\":1}";
        JsonNode r = send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PENDING", productLine, ""), 200);
        assertEquals("Bono 10 sesiones", r.get("lines").get(0).get("name").asText());
        assertEquals(0, new BigDecimal("250").compareTo(r.get("total").decimalValue()));
        assertEquals(p.getId(), r.get("lines").get(0).get("refId").asLong());
        send("POST", "/api/receipts/preview", otherTrainer, receipt(otherClientId, "PENDING", productLine, ""), 404);
        send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PENDING", "{\"kind\":\"PRODUCT\",\"refId\":" + archived.getId() + ",\"quantity\":1}", ""), 409);
        send("POST", "/api/receipts/preview", trainer, receipt(clientId, "PENDING", "{\"kind\":\"PRODUCT\",\"quantity\":1}", ""), 400);

        Coupon c = new Coupon();
        c.setTrainerId(trainerId); c.setCode("VERANO20"); c.setType("PERCENT"); c.setValue(new BigDecimal("20")); c.setAppliesTo("PRODUCTS");
        c.setMaxUses(1);
        c = coupons.save(c);
        String withCoupon = ",\"couponId\":" + c.getId();
        send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", productLine, withCoupon + ",\"discountPct\":5"), 400);
        send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", free("Libre", "10", 1), withCoupon), 409);
        JsonNode ok = send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", productLine, withCoupon), 201);
        assertEquals("VERANO20", ok.get("discountName").asText());
        assertEquals(0, new BigDecimal("200.00").compareTo(ok.get("total").decimalValue()));
        assertEquals(1, coupons.findById(c.getId()).orElseThrow().getUses());
        // Agotado (maxUses = 1).
        send("POST", "/api/receipts", trainer, receipt(clientId, "PENDING", productLine, withCoupon), 409);
        send("POST", "/api/receipts", otherTrainer, receipt(otherClientId, "PENDING", free("X", "1", 1), withCoupon), 404);
    }

    @Test void lafachadaDePagosSigueFuncionandoParaElCliente() throws Exception {
        JsonNode pay = send("POST", "/api/payments", trainer,
                "{\"clientId\":" + clientId + ",\"amount\":45,\"concept\":\"Cuota octubre\",\"dueDate\":\"" + today.plusDays(5) + "\"}", 201);
        long id = pay.get("id").asLong();
        assertEquals("Cuota octubre", pay.get("concept").asText());
        assertEquals(trainerId, pay.get("trainerId").asLong());

        JsonNode rec = send("GET", "/api/receipts/" + id, trainer, null, 200);
        assertEquals("FREE", rec.get("lines").get(0).get("kind").asText());
        assertEquals("PENDING", rec.get("status").asText());

        JsonNode mine = send("GET", "/api/payments", client, null, 200);
        assertEquals(1, mine.get("totalElements").asInt());
        assertEquals("Cuota octubre", mine.get("content").get(0).get("concept").asText());

        // Borrar un pendiente = anularlo (ya no se lista); uno cobrado da 409.
        send("DELETE", "/api/payments/" + id, trainer, null, 204);
        assertEquals(0, send("GET", "/api/payments", client, null, 200).get("totalElements").asInt());
        assertEquals("VOID", send("GET", "/api/receipts/" + id, trainer, null, 200).get("status").asText());

        long id2 = send("POST", "/api/payments", trainer,
                "{\"clientId\":" + clientId + ",\"amount\":45,\"concept\":\"Cuota noviembre\",\"dueDate\":\"" + today + "\"}", 201).get("id").asLong();
        send("POST", "/api/payments/" + id2 + "/paid", trainer, null, 200);
        send("POST", "/api/payments/" + id2 + "/paid", trainer, null, 409);
        send("DELETE", "/api/payments/" + id2, trainer, null, 409);
        send("DELETE", "/api/payments/" + id2, otherTrainer, null, 404);
    }

    @Test void exportarEscapaFormulasYLimitaElRango() throws Exception {
        send("POST", "/api/receipts", trainer, receipt(clientId, "PAID", free("=HYPERLINK(\\\"http://x\\\")", "12.5", 1), ""), 201);
        MvcResult csv = raw("GET", "/api/receipts/export?format=csv&from=" + today + "&to=" + today, trainer, null, 200);
        String text = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿"), "CSV con BOM");
        assertTrue(text.contains("'=HYPERLINK"), "Texto con = antepuesto con '");
        assertTrue(text.contains("12,50"), "Coma decimal");
        assertTrue(csv.getResponse().getHeader("Content-Disposition").contains("attachment"));

        byte[] xlsx = raw("GET", "/api/receipts/export?format=xlsx&from=" + today + "&to=" + today, trainer, null, 200).getResponse().getContentAsByteArray();
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);

        send("GET", "/api/receipts/export?format=pdf", trainer, null, 400);
        send("GET", "/api/receipts?from=" + today.minusDays(400) + "&to=" + today, trainer, null, 400);
        send("GET", "/api/receipts?from=" + today + "&to=" + today.minusDays(1), trainer, null, 400);
    }

    @Test void movimientosDelAdministrador() throws Exception {
        AppUser admin = users.findByEmailIgnoreCase("admin").orElseThrow();
        String adminToken = token(admin.getId(), Role.ADMIN);
        send("POST", "/api/receipts", adminToken, receipt(clientId, "PAID", free("Sesión admin", "80", 1), ""), 201);
        PlatformServiceEntry s = new PlatformServiceEntry();
        s.setName("Render-" + UUID.randomUUID().toString().substring(0, 6)); s.setPlan("Starter");
        s = platformServices.save(s);
        PlatformPayment pp = new PlatformPayment();
        pp.setServiceId(s.getId()); pp.setPaymentDate(today); pp.setConcept("Plan Starter"); pp.setAmount(new BigDecimal("7.00")); pp.setStatus("PAID");
        platformPayments.save(pp);

        JsonNode m = send("GET", "/api/receipts/movements?from=" + today + "&to=" + today, adminToken, null, 200);
        boolean income = false, expense = false;
        for (JsonNode x : m.get("content")) {
            if ("INCOME".equals(x.get("type").asText()) && x.get("description").asText().contains("Sesión admin")) income = true;
            if ("EXPENSE".equals(x.get("type").asText()) && x.get("concept").asText().equals(s.getName())) {
                expense = true;
                assertEquals(0, new BigDecimal("-7.00").compareTo(x.get("amount").decimalValue()));
            }
        }
        assertTrue(income && expense);
        JsonNode onlyExpenses = send("GET", "/api/receipts/movements?types=EXPENSE&from=" + today + "&to=" + today, adminToken, null, 200);
        for (JsonNode x : onlyExpenses.get("content")) assertEquals("EXPENSE", x.get("type").asText());
    }
}
