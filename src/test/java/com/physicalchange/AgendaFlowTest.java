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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Agenda contra la base de datos: tipos de sesión, eventos recurrentes, plazas y cola, horarios y actividad (docs/api-agenda.md). */
@SpringBootTest
@AutoConfigureMockMvc
class AgendaFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ClientProfileRepository profiles;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx;
    private Long trainerId, clientId, client2Id, otherTrainerId, otherClientId;
    private String trainer, client, client2, otherTrainer, otherClient;
    /** Lunes de la semana que viene: las sesiones están en el futuro y se pueden reservar. */
    private final LocalDate monday = LocalDate.now().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        client2Id = save("c2-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        client2 = token(client2Id, Role.CLIENT);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
        otherClient = token(otherClientId, Role.CLIENT);
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
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private long typeId(String auth, String name) throws Exception {
        for (JsonNode t : send("GET", "/api/agenda/session-types", auth, null, 200)) if (t.get("name").asText().equals(name)) return t.get("id").asLong();
        throw new AssertionError("Sin tipo " + name);
    }

    private String session(long typeId, LocalDate date, String extra) {
        return "{\"kind\":\"SESSION\",\"title\":\"Sesión inicial grupal\",\"emoji\":\"🎯\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + typeId +
                ",\"cover\":\"gym\",\"date\":\"" + date + "\",\"startTime\":\"18:30\",\"endTime\":\"20:00\",\"bookable\":\"YES\"" + extra + "}";
    }

    @Test void tiposDeSesion() throws Exception {
        JsonNode list = send("GET", "/api/agenda/session-types", trainer, null, 200);
        assertEquals(5, list.size());                                                     // por defecto
        send("POST", "/api/agenda/session-types", trainer, "{\"name\":\"yoga\",\"color\":\"#3FAE6A\"}", 409);
        send("POST", "/api/agenda/session-types", trainer, "{\"name\":\"Pilates\",\"color\":\"#123456\"}", 400); // fuera de paleta
        long pilates = send("POST", "/api/agenda/session-types", trainer, "{\"name\":\"Pilates\",\"color\":\"#3fae6a\"}", 201).get("id").asLong();
        send("PUT", "/api/agenda/session-types/" + pilates, otherTrainer, "{\"name\":\"X\",\"color\":\"#3FAE6A\"}", 403);
        send("GET", "/api/agenda/session-types", client, null, 403);

        // En uso: no se borra; se sustituye y entonces sí
        long yoga = typeId(trainer, "Yoga");
        send("POST", "/api/agenda/events", trainer, session(yoga, monday, ""), 201);
        send("DELETE", "/api/agenda/session-types/" + yoga, trainer, null, 409);
        send("POST", "/api/agenda/session-types/" + yoga + "/replace", trainer, "{\"targetId\":" + yoga + "}", 400);
        assertEquals(1, send("POST", "/api/agenda/session-types/" + yoga + "/replace", trainer, "{\"targetId\":" + pilates + "}", 200).get("moved").asInt());
        send("DELETE", "/api/agenda/session-types/" + yoga, trainer, null, 204);
        JsonNode ev = send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday, trainer, null, 200).get(0);
        assertEquals("Pilates", ev.get("sessionTypeName").asText());
        assertEquals("#3FAE6A", ev.get("color").asText());
    }

    @Test void serieConPlazasYCola() throws Exception {
        long type = typeId(trainer, "Entrenamiento personal");
        // Lunes y miércoles durante dos semanas = 4 sesiones, 1 plaza y 2 clientes: uno reservado y otro en cola
        String body = session(type, monday, ",\"capacity\":1,\"recurrence\":{\"weekdays\":[1,3],\"until\":\"" + monday.plusDays(9) +
                "\"},\"clientIds\":[" + clientId + "," + client2Id + "]");
        JsonNode created = send("POST", "/api/agenda/events", trainer, body, 201);
        assertEquals(4, created.size());
        JsonNode first = created.get(0);
        assertTrue(first.get("recurrent").asBoolean());
        assertEquals(1, first.get("booked").asInt());
        assertEquals(1, first.get("waitlist").asInt());
        long eventId = first.get("id").asLong();

        // KPIs del rango
        JsonNode stats = send("GET", "/api/agenda/stats?from=" + monday + "&to=" + monday.plusDays(13), trainer, null, 200);
        assertEquals(4, stats.get("totalSessions").asInt());
        assertEquals(4, stats.get("booked").asInt());
        assertEquals(4, stats.get("waitlist").asInt());

        // El cliente reservado cancela: entra el de la cola
        JsonNode detail = send("GET", "/api/agenda/events/" + eventId, trainer, null, 200);
        long booking = 0;
        for (JsonNode b : detail.get("bookings")) if (b.get("clientId").asLong() == clientId) booking = b.get("id").asLong();
        send("PATCH", "/api/agenda/bookings/" + booking, client, "{\"status\":\"ATTENDED\"}", 403);   // el cliente solo cancela
        send("PATCH", "/api/agenda/bookings/" + booking, client2, "{\"status\":\"CANCELLED\"}", 403);  // ajena
        assertEquals("CANCELLED", send("PATCH", "/api/agenda/bookings/" + booking, client, "{\"status\":\"CANCELLED\"}", 200).get("status").asText());
        detail = send("GET", "/api/agenda/events/" + eventId, trainer, null, 200);
        for (JsonNode b : detail.get("bookings")) if (b.get("clientId").asLong() == client2Id) assertEquals("BOOKED", b.get("status").asText());
        send("PATCH", "/api/agenda/bookings/" + booking, trainer, "{\"status\":\"BOOKED\"}", 409);    // sin plazas

        // Asistencia: 1 de 1 en esa sesión
        long b2 = 0;
        for (JsonNode b : detail.get("bookings")) if (b.get("clientId").asLong() == client2Id) b2 = b.get("id").asLong();
        send("PATCH", "/api/agenda/bookings/" + b2, trainer, "{\"status\":\"ATTENDED\"}", 200);
        stats = send("GET", "/api/agenda/stats?from=" + monday + "&to=" + monday, trainer, null, 200);
        assertEquals(100, stats.get("attendancePct").asInt());
        assertEquals(1, stats.get("cancelled").asInt());

        // Editar "esta y las siguientes" desde la segunda: cambian 3; borrar las siguientes desde la tercera: quedan 2
        long second = created.get(1).get("id").asLong();
        JsonNode updated = send("PUT", "/api/agenda/events/" + second + "?scope=FOLLOWING", trainer,
                session(type, monday, ",\"capacity\":1").replace("Sesión inicial grupal", "Grupal renovada"), 200);
        assertEquals(3, updated.size());
        assertEquals(monday.plusDays(2).toString(), updated.get(0).get("start").asText().substring(0, 10));   // conserva su día
        send("DELETE", "/api/agenda/events/" + created.get(2).get("id").asLong() + "?scope=FOLLOWING", trainer, null, 204);
        JsonNode left = send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday.plusDays(13), trainer, null, 200);
        assertEquals(2, left.size());
        assertEquals("Grupal renovada", left.get(1).get("title").asText());

        // Filtros
        assertEquals(0, send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday.plusDays(13) + "&modes=ONLINE", trainer, null, 200).size());
        assertEquals(2, send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday.plusDays(13) + "&recurrent=true", trainer, null, 200).size());
    }

    @Test void reservasDelClienteYPermisos() throws Exception {
        long type = typeId(trainer, "Yoga");
        long open = send("POST", "/api/agenda/events", trainer, session(type, monday, ""), 201).get(0).get("id").asLong();
        long closed = send("POST", "/api/agenda/events", trainer, session(type, monday, "").replace("\"YES\"", "\"NO\""), 201).get(0).get("id").asLong();
        long reminder = send("POST", "/api/agenda/events", trainer,
                "{\"kind\":\"REMINDER\",\"title\":\"Revisar planes\",\"date\":\"" + monday + "\",\"startTime\":\"09:00\",\"endTime\":\"09:30\",\"bookable\":\"NO\"}", 201)
                .get(0).get("id").asLong();

        send("POST", "/api/agenda/events/" + open + "/bookings", client, "{\"clientId\":" + clientId + "}", 201);
        send("POST", "/api/agenda/events/" + open + "/bookings", client, "{\"clientId\":" + clientId + "}", 409);      // repetida
        send("POST", "/api/agenda/events/" + open + "/bookings", client, "{\"clientId\":" + client2Id + "}", 403);     // para otro
        send("POST", "/api/agenda/events/" + closed + "/bookings", client, "{\"clientId\":" + clientId + "}", 403);    // no reservable
        send("POST", "/api/agenda/events/" + open + "/bookings", otherClient, "{\"clientId\":" + otherClientId + "}", 403); // de otro profesional
        send("POST", "/api/agenda/events/" + reminder + "/bookings", trainer, "{\"clientId\":" + clientId + "}", 400);  // no es sesión
        send("POST", "/api/agenda/events/" + open + "/bookings", trainer, "{\"clientId\":" + otherClientId + "}", 403); // cliente ajeno

        // Otro profesional no ve ni toca el evento
        send("GET", "/api/agenda/events/" + open, otherTrainer, null, 403);
        send("DELETE", "/api/agenda/events/" + open, otherTrainer, null, 403);
        assertEquals(0, send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday, otherTrainer, null, 200).size());
        send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday, client, null, 403);

        // Compatibilidad: el calendario del cliente incluye la sesión reservada
        JsonNode cal = send("GET", "/api/calendar?from=" + monday + "&to=" + monday, client, null, 200);
        assertTrue(cal.toString().contains("\"type\":\"EVENT\""));
        assertTrue(cal.toString().contains("Sesión inicial grupal"));

        // Actividad: el cliente aparece como actor; filtro por perfil
        JsonNode act = send("GET", "/api/agenda/activity?from=" + LocalDate.now() + "&to=" + LocalDate.now(), trainer, null, 200);
        assertTrue(act.size() >= 4);
        JsonNode clients = send("GET", "/api/agenda/activity?from=" + LocalDate.now() + "&to=" + LocalDate.now() + "&profile=CLIENTS", trainer, null, 200);
        assertEquals(1, clients.size());
        assertEquals("BOOKED", clients.get(0).get("action").asText());
        assertEquals(0, send("GET", "/api/agenda/activity?from=" + LocalDate.now() + "&to=" + LocalDate.now(), otherTrainer, null, 200).size());
    }

    @Test void validaciones() throws Exception {
        long type = typeId(trainer, "Yoga");
        long foreign = typeId(otherTrainer, "Yoga");
        send("POST", "/api/agenda/events", trainer, session(type, monday, "").replace("20:00", "17:00"), 400);       // fin antes de inicio
        send("POST", "/api/agenda/events", trainer, session(foreign, monday, ""), 400);                            // tipo ajeno
        send("POST", "/api/agenda/events", trainer, session(type, monday, "").replace("\"gym\"", "\"file:999999\""), 404); // portada inexistente
        send("POST", "/api/agenda/events", trainer, session(type, monday, "").replace("\"IN_PERSON\"", "\"ONLINE\"") .replace("}", ",\"meetingUrl\":\"javascript:alert(1)\"}"), 400);
        send("POST", "/api/agenda/events", trainer, session(type, monday, ",\"recurrence\":{\"weekdays\":[1],\"until\":\"" + monday.plusYears(2) + "\"}"), 400);
        send("POST", "/api/agenda/events", trainer, session(type, monday, "").replace("\"YES\"", "\"TAGS\""), 400);  // sin etiquetas
        send("POST", "/api/agenda/events", trainer, "{\"kind\":\"PRIVATE\",\"title\":\"Médico\",\"date\":\"" + monday + "\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"bookable\":\"NO\"}", 400); // falta showAs
        send("POST", "/api/agenda/events", trainer, "{\"kind\":\"MEETING\",\"title\":\"Equipo\",\"date\":\"" + monday + "\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"bookable\":\"NO\"}", 400); // falta modo
        send("GET", "/api/agenda/events?from=" + monday + "&to=" + monday.minusDays(1), trainer, null, 400);
        long id = send("POST", "/api/agenda/events", trainer, session(type, monday, ""), 201).get(0).get("id").asLong();
        send("PUT", "/api/agenda/events/" + id + "?scope=ALL", trainer, session(type, monday, ""), 400);
    }

    @Test void horariosDeCitas() throws Exception {
        long type = typeId(trainer, "Entrenamiento personal");
        long tag = send("POST", "/api/tags", trainer, "{\"name\":\"online-" + sfx + "\",\"automations\":null}", 201).get("id").asLong();
        ClientProfile p = profiles.findById(clientId).orElseGet(() -> new ClientProfile(clientId));
        p.getTagIds().add(tag);
        profiles.save(p);

        String body = "{\"title\":\"Asesoramiento online\",\"emoji\":\"✍️\",\"mode\":\"ONLINE\",\"source\":\"VIDEO\",\"meetingUrl\":\"https://meet.example/abc\"," +
                "\"sessionTypeId\":" + type + ",\"description\":\"Videollamada individual\",\"cover\":\"brand\",\"startDate\":\"" + monday +
                "\",\"endDate\":\"" + monday.plusDays(30) + "\",\"durationMin\":30,\"slots\":[{\"weekday\":1,\"from\":\"09:00\",\"to\":\"13:00\"}]," +
                "\"visibility\":\"TAGS\",\"tagIds\":[" + tag + "]}";
        send("POST", "/api/agenda/schedules", trainer, body.replace("\"source\":\"VIDEO\",", ""), 400);              // online sin fuente
        send("POST", "/api/agenda/schedules", trainer, body.replace("\"to\":\"13:00\"", "\"to\":\"08:00\""), 400);   // franja al revés
        JsonNode s = send("POST", "/api/agenda/schedules", trainer, body, 201);
        long id = s.get("id").asLong();
        assertEquals("Entrenamiento personal", s.get("sessionTypeName").asText());
        assertEquals(1, s.get("slots").size());

        JsonNode detail = send("GET", "/api/agenda/schedules/" + id, trainer, null, 200);
        assertEquals(1, detail.get("availableFor").size());                         // solo el cliente con la etiqueta
        assertEquals(clientId, detail.get("availableFor").get(0).get("id").asLong());
        send("PUT", "/api/agenda/schedules/" + id, trainer, body.replace("\"TAGS\"", "\"ALL\""), 200);
        assertEquals(2, send("GET", "/api/agenda/schedules/" + id, trainer, null, 200).get("availableFor").size());

        assertFalse(send("PATCH", "/api/agenda/schedules/" + id + "/active", trainer, "{\"active\":false}", 200).get("active").asBoolean());
        send("GET", "/api/agenda/schedules/" + id, otherTrainer, null, 403);
        send("DELETE", "/api/agenda/session-types/" + type, trainer, null, 409);   // el horario lo usa
        send("DELETE", "/api/agenda/schedules/" + id, otherTrainer, null, 403);
        send("DELETE", "/api/agenda/schedules/" + id, trainer, null, 204);
        assertEquals(0, send("GET", "/api/agenda/schedules", trainer, null, 200).size());
    }

    /** En producción el profesional es el ADMIN y sus clientes no tienen trainerId (como en ClientProfileService). */
    @Test void elAdministradorComoProfesional() throws Exception {
        Long adminId = save("adm-" + sfx + "@test", Role.ADMIN, null).getId();
        String admin = token(adminId, Role.ADMIN);
        AppUser solo = save("solo-" + sfx + "@test", Role.CLIENT, null);
        ClientProfile p = profiles.findById(solo.getId()).orElseGet(() -> new ClientProfile(solo.getId()));
        p.setBirthDate(monday.minusYears(30));
        profiles.save(p);

        long type = typeId(admin, "Yoga");
        long ev = send("POST", "/api/agenda/events", admin, session(type, monday, ""), 201).get(0).get("id").asLong();
        send("POST", "/api/agenda/events/" + ev + "/bookings", token(solo.getId(), Role.CLIENT), "{\"clientId\":" + solo.getId() + "}", 201);
        assertTrue(send("GET", "/api/agenda/birthdays?from=" + monday + "&to=" + monday, admin, null, 200).toString().contains("\"clientId\":" + solo.getId()));

        String body = "{\"title\":\"Citas\",\"mode\":\"IN_PERSON\",\"sessionTypeId\":" + type + ",\"cover\":\"gym\",\"startDate\":\"" + monday +
                "\",\"endDate\":\"" + monday.plusDays(7) + "\",\"durationMin\":30,\"slots\":[{\"weekday\":2,\"from\":\"10:00\",\"to\":\"12:00\"}],\"visibility\":\"ALL\"}";
        long s = send("POST", "/api/agenda/schedules", admin, body, 201).get("id").asLong();
        assertTrue(send("GET", "/api/agenda/schedules/" + s, admin, null, 200).get("availableFor").toString().contains("\"id\":" + solo.getId()));
    }

    @Test void cumpleanos() throws Exception {
        ClientProfile p = profiles.findById(clientId).orElseGet(() -> new ClientProfile(clientId));
        p.setBirthDate(LocalDate.of(1992, 2, 29));
        profiles.save(p);
        LocalDate from = LocalDate.of(2027, 2, 1), to = LocalDate.of(2027, 3, 1);
        JsonNode b = send("GET", "/api/agenda/birthdays?from=" + from + "&to=" + to, trainer, null, 200);
        assertEquals(1, b.size());
        assertEquals("2027-02-28", b.get(0).get("date").asText());                // año no bisiesto
        assertEquals(0, send("GET", "/api/agenda/birthdays?from=" + from + "&to=" + to, otherTrainer, null, 200).size());
    }
}
