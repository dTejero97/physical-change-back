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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seguimiento diario del cliente y sesiones reservables (T201, docs/api-cliente-seguimiento.md).
 * Reloj fijo: 2026-10-30 23:30 UTC = 2026-10-31 00:30 en Madrid (CET). "Hoy" para la plataforma es el 31, aunque en UTC sea el 30.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DailyLogTest {
    static final Instant NOW = Instant.parse("2026-10-30T23:30:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneId.of("Europe/Madrid")); }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ClientProfileRepository profiles;
    @Autowired ProgressEntryRepository progress;
    @Autowired ClientDailyLogRepository logs;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String sfx, admin, trainer, otherTrainer, collaborator, client, client2, otherClient;
    Long trainerId, clientId, client2Id, otherClientId;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        admin = token(save("dl-a-" + sfx + "@test", Role.ADMIN, null));
        AppUser t = save("dl-t-" + sfx + "@test", Role.TRAINER, null);
        AppUser o = save("dl-o-" + sfx + "@test", Role.TRAINER, null);
        AppUser col = save("dl-col-" + sfx + "@test", Role.COLLABORATOR, null);
        AppUser c = save("dl-c-" + sfx + "@test", Role.CLIENT, t.getId());
        AppUser c2 = save("dl-c2-" + sfx + "@test", Role.CLIENT, t.getId());
        AppUser oc = save("dl-oc-" + sfx + "@test", Role.CLIENT, o.getId());
        trainerId = t.getId(); clientId = c.getId(); client2Id = c2.getId(); otherClientId = oc.getId();
        trainer = token(t); otherTrainer = token(o); collaborator = token(col); client = token(c); client2 = token(c2); otherClient = token(oc);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setNutritionEnabled(true); u.setTrainingEnabled(true); u.setPasswordHash(enc.encode("Password1!"));
        return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url);
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.contentType("application/json").characterEncoding("UTF-8").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isBlank() ? null : json.readTree(s);
    }
    private JsonNode put(String auth, LocalDate d, String body, int expected) throws Exception {
        return send("PUT", "/api/me/daily-log/" + d, auth, body, expected);
    }
    private static boolean same(String expected, JsonNode n) { return new BigDecimal(expected).compareTo(n.decimalValue()) == 0; }

    // ---------- D1 · diario del día ----------

    @Test void diaSinDatosDevuelveVacioNo404() throws Exception {
        JsonNode d = send("GET", "/api/me/daily-log/" + TODAY, client, null, 200);
        assertEquals(TODAY.toString(), d.get("date").asText());
        for (String f : List.of("weightKg", "sleepMinutes", "mood", "updatedAt")) assertTrue(d.get(f).isNull(), f);
        assertFalse(d.get("restDay").asBoolean());
        assertFalse(d.get("logged").asBoolean());
    }

    @Test void guardarMiDiaYRepetirloNoDuplica() throws Exception {
        LocalDate day = TODAY.minusDays(2);
        JsonNode d = put(client, day, "{\"weightKg\":75.5,\"sleepMinutes\":420,\"mood\":4,\"restDay\":false}", 200);
        assertTrue(same("75.5", d.get("weightKg")));
        assertEquals(420, d.get("sleepMinutes").asInt());
        assertEquals(4, d.get("mood").asInt());
        assertTrue(d.get("logged").asBoolean());
        assertEquals("2026-10-31T00:30:00", d.get("updatedAt").asText());   // hora de Madrid, no UTC

        // Segundo guardado del mismo día: actualiza la misma fila del diario y el mismo registro de peso.
        d = put(client, day, "{\"weightKg\":75.1,\"sleepMinutes\":390,\"mood\":2,\"restDay\":true}", 200);
        assertTrue(same("75.1", d.get("weightKg")));
        assertEquals(2, d.get("mood").asInt());
        assertTrue(d.get("restDay").asBoolean());
        assertEquals(1, logs.findByClientIdAndLogDateBetween(clientId, day, day).size());
        assertEquals(1, progress.findByClientIdAndEntryDateBetween(clientId, day, day).size());

        // Sin weightKg no se toca el peso; sueño y ánimo ausentes se vacían.
        d = put(client, day, "{\"restDay\":true}", 200);
        assertTrue(same("75.1", d.get("weightKg")));
        assertTrue(d.get("sleepMinutes").isNull());
        assertTrue(d.get("mood").isNull());
        assertEquals(d.toString(), send("GET", "/api/me/daily-log/" + day, client, null, 200).toString());
    }

    @Test void vaciarElDiaBorraLaFilaPeroNoElPeso() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        put(client, day, "{\"weightKg\":80,\"sleepMinutes\":400}", 200);
        JsonNode d = put(client, day, "{\"sleepMinutes\":null,\"mood\":null,\"restDay\":false}", 200);
        assertTrue(logs.findByClientIdAndLogDate(clientId, day).isEmpty());
        assertTrue(same("80", d.get("weightKg")));
        assertTrue(d.get("logged").asBoolean());                          // sigue teniendo peso
        assertTrue(d.get("updatedAt").isNull());
        // Un día solo con descanso cuenta como registrado.
        assertTrue(put(client, TODAY.minusDays(3), "{\"restDay\":true}", 200).get("logged").asBoolean());
    }

    @Test void validacionesYFechas() throws Exception {
        put(client, TODAY, "{\"mood\":6}", 400);
        put(client, TODAY, "{\"mood\":0}", 400);
        put(client, TODAY, "{\"sleepMinutes\":1441}", 400);
        put(client, TODAY, "{\"sleepMinutes\":-1}", 400);
        put(client, TODAY, "{\"weightKg\":10}", 400);
        put(client, LocalDate.of(1999, 12, 31), "{\"mood\":3}", 400);
        send("PUT", "/api/me/daily-log/2026-13-01", client, "{\"mood\":3}", 400);
        send("GET", "/api/me/daily-log/hoy", client, null, 400);
        put(client, TODAY.plusDays(1), "{\"mood\":3}", 400);              // futuro
        assertTrue(logs.findByClientIdAndLogDateBetween(clientId, LocalDate.of(1999, 1, 1), TODAY.plusDays(5)).isEmpty());
        // Límites admitidos
        put(client, TODAY, "{\"sleepMinutes\":0,\"mood\":1}", 200);
        put(client, TODAY, "{\"sleepMinutes\":1440,\"mood\":5}", 200);
    }

    /** 23:30 UTC del 30 ya es el 31 en Madrid: el 31 se admite y el resumen dice que hoy es el 31. */
    @Test void hoyEnHoraDeMadridNoEnUtc() throws Exception {
        assertEquals(LocalDate.of(2026, 10, 30), LocalDate.ofInstant(NOW, ZoneOffset.UTC));
        put(client, TODAY, "{\"mood\":5}", 200);
        put(client, TODAY.plusDays(1), "{\"mood\":5}", 400);
        assertEquals(TODAY.toString(), send("GET", "/api/me/daily-log?month=2026-11", client, null, 200).get("today").asText());
    }

    // ---------- D3 · resumen mensual ----------

    @Test void mesConDiasVacios() throws Exception {
        put(client, LocalDate.of(2026, 10, 2), "{\"weightKg\":75.9,\"sleepMinutes\":450,\"mood\":3}", 200);
        put(client, LocalDate.of(2026, 10, 15), "{\"restDay\":true}", 200);
        put(client, LocalDate.of(2026, 9, 30), "{\"mood\":4}", 200);       // otro mes: no aparece
        JsonNode m = send("GET", "/api/me/daily-log?month=2026-10", client, null, 200);
        assertEquals("2026-10", m.get("month").asText());
        assertEquals(TODAY.toString(), m.get("today").asText());
        JsonNode days = m.get("days");
        assertEquals(31, days.size());
        List<String> logged = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            assertEquals(LocalDate.of(2026, 10, i + 1).toString(), days.get(i).get("date").asText());
            if (days.get(i).get("logged").asBoolean()) logged.add(days.get(i).get("date").asText());
        }
        assertEquals(List.of("2026-10-02", "2026-10-15"), logged);
        assertTrue(same("75.9", days.get(1).get("weightKg")));
        assertEquals(450, days.get(1).get("sleepMinutes").asInt());
        assertTrue(days.get(14).get("restDay").asBoolean());
        assertTrue(days.get(14).get("weightKg").isNull());

        // Peso guardado por /api/progress también sale en el mes.
        send("POST", "/api/progress", client, "{\"clientId\":" + clientId + ",\"entryDate\":\"2026-10-20\",\"weightKg\":76.2}", 201);
        assertTrue(send("GET", "/api/me/daily-log?month=2026-10", client, null, 200).get("days").get(19).get("logged").asBoolean());

        assertEquals(28, send("GET", "/api/me/daily-log?month=2027-02", client, null, 200).get("days").size());
        assertEquals(29, send("GET", "/api/me/daily-log?month=2028-02", client, null, 200).get("days").size());
        send("GET", "/api/me/daily-log?month=2026-13", client, null, 400);
        send("GET", "/api/me/daily-log?month=octubre", client, null, 400);
        send("GET", "/api/me/daily-log?month=1999-12", client, null, 400);
        send("GET", "/api/me/daily-log", client, null, 400);
    }

    // ---------- aislamiento y permisos ----------

    @Test void cadaClienteSoloVeLoSuyo() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        put(client, day, "{\"weightKg\":70,\"sleepMinutes\":480,\"mood\":5}", 200);
        JsonNode other = send("GET", "/api/me/daily-log/" + day, client2, null, 200);
        assertFalse(other.get("logged").asBoolean());
        assertTrue(other.get("weightKg").isNull());
        for (JsonNode d : send("GET", "/api/me/daily-log?month=2026-10", client2, null, 200).get("days")) assertFalse(d.get("logged").asBoolean());
        // El otro cliente escribe su día sin tocar el del primero.
        put(client2, day, "{\"mood\":1}", 200);
        assertEquals(5, send("GET", "/api/me/daily-log/" + day, client, null, 200).get("mood").asInt());
        // Un cliente no puede leer el diario de otro por la ruta del profesional.
        send("GET", "/api/clients/" + clientId + "/daily-log/" + day, client2, null, 403);
        send("GET", "/api/clients/" + clientId + "/daily-log?month=2026-10", client, null, 403);
        // /api/me es solo para clientes.
        send("GET", "/api/me/daily-log/" + day, trainer, null, 403);
        put(trainer, day, "{\"mood\":3}", 403);
        put(admin, day, "{\"mood\":3}", 403);
        send("GET", "/api/me/daily-log/" + day, null, null, 401);
    }

    @Test void elProfesionalLeeElDiarioDeSuCliente() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        put(client, day, "{\"weightKg\":70,\"sleepMinutes\":480,\"mood\":5,\"restDay\":true}", 200);
        JsonNode d = send("GET", "/api/clients/" + clientId + "/daily-log/" + day, trainer, null, 200);
        assertEquals(5, d.get("mood").asInt());
        assertTrue(same("70", d.get("weightKg")));
        assertEquals(31, send("GET", "/api/clients/" + clientId + "/daily-log?month=2026-10", trainer, null, 200).get("days").size());
        assertEquals(5, send("GET", "/api/clients/" + clientId + "/daily-log/" + day, admin, null, 200).get("mood").asInt());
        send("GET", "/api/clients/" + clientId + "/daily-log/" + day, otherTrainer, null, 403);
        send("GET", "/api/clients/" + clientId + "/daily-log?month=2026-10", otherTrainer, null, 403);
        send("GET", "/api/clients/" + clientId + "/daily-log/" + day, collaborator, null, 403);
        send("GET", "/api/clients/999999999/daily-log/" + day, trainer, null, 404);
        // Solo lectura: no hay escritura del profesional.
        send("PUT", "/api/clients/" + clientId + "/daily-log/" + day, trainer, "{\"mood\":1}", 405);
    }

    // ---------- D2 · peso único por día ----------

    @Test void pesoUnicoPorDiaEnProgreso() throws Exception {
        String url = "/api/progress";
        JsonNode a = send("POST", url, client, "{\"clientId\":" + clientId + ",\"entryDate\":\"2026-10-10\",\"weightKg\":80}", 201);
        JsonNode b = send("POST", url, trainer, "{\"clientId\":" + clientId + ",\"entryDate\":\"2026-10-10\",\"bodyFatPct\":18.5,\"notes\":\"Báscula del gimnasio\"}", 201);
        assertEquals(a.get("id").asLong(), b.get("id").asLong());          // mismo registro
        assertTrue(same("80", b.get("weightKg")));                         // el peso se conserva
        assertTrue(same("18.5", b.get("bodyFatPct")));
        JsonNode c = send("POST", url, client, "{\"clientId\":" + clientId + ",\"entryDate\":\"2026-10-10\",\"weightKg\":79.4}", 201);
        assertEquals(a.get("id").asLong(), c.get("id").asLong());
        assertTrue(same("79.4", c.get("weightKg")));
        assertEquals("Báscula del gimnasio", c.get("notes").asText());
        assertEquals(1, send("GET", "/api/progress/client/" + clientId, client, null, 200).size());
        // El diario escribe en el mismo registro.
        put(client, LocalDate.of(2026, 10, 10), "{\"weightKg\":79}", 200);
        assertEquals(1, progress.findByClientIdAndEntryDateBetween(clientId, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)).size());
        assertTrue(same("79", send("GET", "/api/progress/client/" + clientId, trainer, null, 200).get(0).get("weightKg")));
        // Otro cliente no puede escribir en el día de este.
        send("POST", url, client2, "{\"clientId\":" + clientId + ",\"entryDate\":\"2026-10-10\",\"weightKg\":50}", 403);
    }

    @Test void laBaseDeDatosRechazaDosPesosDelMismoDia() {
        ProgressEntry e1 = new ProgressEntry(); e1.setClientId(clientId); e1.setEntryDate(TODAY); e1.setWeightKg(new BigDecimal("70"));
        progress.saveAndFlush(e1);
        ProgressEntry e2 = new ProgressEntry(); e2.setClientId(clientId); e2.setEntryDate(TODAY); e2.setWeightKg(new BigDecimal("71"));
        assertThrows(DataIntegrityViolationException.class, () -> progress.saveAndFlush(e2));
        ClientDailyLog l1 = log(clientId, TODAY);
        logs.saveAndFlush(l1);
        assertThrows(DataIntegrityViolationException.class, () -> logs.saveAndFlush(log(clientId, TODAY)));
    }

    private static ClientDailyLog log(Long clientId, LocalDate d) {
        ClientDailyLog l = new ClientDailyLog(); l.setClientId(clientId); l.setLogDate(d); l.setMood(3);
        l.setCreatedAt(LocalDateTime.now()); l.setUpdatedAt(LocalDateTime.now());
        return l;
    }

    // ---------- D17 · sesiones reservables ----------

    private long typeId(String auth) throws Exception {
        return send("GET", "/api/agenda/session-types", auth, null, 200).get(0).get("id").asLong();
    }
    private long event(String auth, long type, LocalDate date, String start, String extra) throws Exception {
        String body = "{\"kind\":\"SESSION\",\"title\":\"Sesión " + date + " " + start + "\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + type +
                ",\"cover\":\"gym\",\"date\":\"" + date + "\",\"startTime\":\"" + start + "\",\"endTime\":\"23:59\",\"bookable\":\"YES\"" + extra + "}";
        return send("POST", "/api/agenda/events", auth, body, 201).get(0).get("id").asLong();
    }
    private static List<Long> ids(JsonNode list) {
        List<Long> out = new ArrayList<>();
        for (JsonNode n : list) out.add(n.get("id").asLong());
        return out;
    }

    @Test void sesionesReservablesDelCliente() throws Exception {
        long type = typeId(trainer);
        LocalDate d = TODAY.plusDays(2);
        long tag = send("POST", "/api/tags", trainer, "{\"name\":\"grupo-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        long otherTag = send("POST", "/api/tags", trainer, "{\"name\":\"otro-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        ClientProfile p = profiles.findById(clientId).orElseGet(() -> new ClientProfile(clientId));
        p.getTagIds().add(tag);
        profiles.save(p);

        long open = event(trainer, type, d, "18:00", ",\"capacity\":1");
        long online = event(trainer, type, d, "19:00", "");
        long tagged = event(trainer, type, d, "20:00", "");
        send("PUT", "/api/agenda/events/" + tagged, trainer, eventBody(type, d, "20:00", ",\"bookable\":\"TAGS\",\"bookableTagIds\":[" + tag + "]"), 200);
        long notMine = event(trainer, type, d, "21:00", "");
        send("PUT", "/api/agenda/events/" + notMine, trainer, eventBody(type, d, "21:00", ",\"bookable\":\"TAGS\",\"bookableTagIds\":[" + otherTag + "]"), 200);
        long closed = event(trainer, type, d, "22:00", "");
        send("PUT", "/api/agenda/events/" + closed, trainer, eventBody(type, d, "22:00", ",\"bookable\":\"NO\""), 200);
        // Ayer en Madrid (30 a las 23:00 = 22:00 UTC, antes de "ahora"): ya empezó.
        long started = event(trainer, type, TODAY.minusDays(1), "23:00", "");
        long otherPro = event(otherTrainer, typeId(otherTrainer), d, "18:00", "");
        send("PUT", "/api/agenda/events/" + online, trainer,
                eventBody(type, d, "19:00", "").replace("\"IN_PERSON\"", "\"ONLINE\"").replace("}", ",\"meetingUrl\":\"https://meet.example/x\"}"), 200);

        String url = "/api/me/agenda/available?from=" + TODAY.minusDays(1) + "&to=" + d;
        JsonNode list = send("GET", url, client, null, 200);
        assertEquals(List.of(open, online, tagged), ids(list));
        JsonNode first = list.get(0);
        assertEquals(1, first.get("capacity").asInt());
        assertEquals(0, first.get("booked").asLong());
        assertEquals(1, first.get("seatsLeft").asInt());
        assertFalse(first.get("full").asBoolean());
        assertTrue(first.get("myBookingId").isNull());
        assertTrue(list.get(1).get("capacity").isNull());
        assertTrue(list.get(1).get("seatsLeft").isNull());
        assertTrue(list.get(1).get("meetingUrl").isNull());               // sin plaza no ve el enlace
        assertEquals("U dl-t-" + sfx + "@test", first.get("organizerName").asText());

        // client2 no tiene la etiqueta: no ve la sesión TAGS.
        assertEquals(List.of(open, online), ids(send("GET", url, client2, null, 200)));
        // El cliente de otro profesional solo ve las de su profesional.
        assertEquals(List.of(otherPro), ids(send("GET", url, otherClient, null, 200)));

        // Reservas por el endpoint existente; la lista refleja plazas, cola y su reserva.
        send("POST", "/api/agenda/events/" + open + "/bookings", client2, "{\"clientId\":" + client2Id + "}", 201);
        JsonNode w = send("POST", "/api/agenda/events/" + open + "/bookings", client, "{\"clientId\":" + clientId + "}", 201);
        assertEquals("WAITLIST", w.get("status").asText());
        long onlineBooking = send("POST", "/api/agenda/events/" + online + "/bookings", client, "{\"clientId\":" + clientId + "}", 201).get("id").asLong();
        send("POST", "/api/agenda/events/" + started + "/bookings", client, "{\"clientId\":" + clientId + "}", 400);   // ya empezó (hora de Madrid)

        list = send("GET", url, client, null, 200);
        first = list.get(0);
        assertEquals(1, first.get("booked").asLong());
        assertEquals(0, first.get("seatsLeft").asInt());
        assertTrue(first.get("full").asBoolean());
        assertEquals("WAITLIST", first.get("myBookingStatus").asText());
        assertEquals(w.get("id").asLong(), first.get("myBookingId").asLong());
        assertEquals("BOOKED", list.get(1).get("myBookingStatus").asText());
        assertEquals("https://meet.example/x", list.get(1).get("meetingUrl").asText());

        // Cancelar: su reserva deja de aparecer.
        send("PATCH", "/api/agenda/bookings/" + onlineBooking, client, "{\"status\":\"CANCELLED\"}", 200);
        JsonNode after = send("GET", url, client, null, 200).get(1);
        assertTrue(after.get("myBookingStatus").isNull());
        assertTrue(after.get("meetingUrl").isNull());
    }

    @Test void reservablesRangoYPermisos() throws Exception {
        send("GET", "/api/me/agenda/available?from=" + TODAY + "&to=" + TODAY.plusDays(92), client, null, 200);
        send("GET", "/api/me/agenda/available?from=" + TODAY + "&to=" + TODAY.plusDays(93), client, null, 400);
        send("GET", "/api/me/agenda/available?from=" + TODAY + "&to=" + TODAY.minusDays(1), client, null, 400);
        send("GET", "/api/me/agenda/available?from=" + TODAY, client, null, 400);
        send("GET", "/api/me/agenda/available?from=" + TODAY + "&to=" + TODAY, trainer, null, 403);
        assertEquals(0, send("GET", "/api/me/agenda/available?from=" + TODAY + "&to=" + TODAY.plusDays(7), client, null, 200).size());
    }

    private String eventBody(long type, LocalDate date, String start, String extra) {
        String base = "{\"kind\":\"SESSION\",\"title\":\"Sesión " + date + " " + start + "\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + type +
                ",\"cover\":\"gym\",\"date\":\"" + date + "\",\"startTime\":\"" + start + "\",\"endTime\":\"23:59\"";
        return base + (extra.contains("\"bookable\"") ? "" : ",\"bookable\":\"YES\"") + extra + "}";
    }
}
